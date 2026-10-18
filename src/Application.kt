import api.avatarApi
import api.lotteryApi
import api.operatorApi
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import game.lottery.model.LotteryTable
import game.lottery.model.LotteryTicketTable
import game.lottery.network.jackpotSocket
import game.lottery.network.lotterySocket
import game.lottery.service.LotteryEngine
import game.mines.model.MinesGameTable
import game.mines.network.minesSocket
import game.plinko.model.PlinkoGameTable
import game.plinko.network.plinkoSocket
import game.pvp.model.PvpGameTable
import game.pvp.network.pvpSocket
import game.pvp.service.PvpEngine
import game.shell.model.ShellGameTable
import game.shell.network.shellSocket
import game.telefly.model.TeleFlyGameTable
import game.telefly.network.teleFlySocket
import game.telefly.service.TeleFlyEngine
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.websocket.WebSockets
import model.BrandTable
import model.PlayerTable
import model.RoundTable
import model.SessionTable
import model.TransactionTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.DatabaseConfig
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import util.Env

fun main() {
    initDatabase()
    embeddedServer(CIO, port = Env.serverPort, host = "0.0.0.0") {
        install(Koin) { modules(appModule) }
        install(ContentNegotiation) { json() }
        // Server pings keep idle game sockets alive through proxy idle timeouts
        // (ingress-nginx defaults to 60s) and detect dead peers between rounds.
        install(WebSockets) {
            pingPeriodMillis = 25_000
            timeoutMillis = 60_000
        }
        operatorApi()
        avatarApi()
        lotteryApi()
        minesSocket()
        shellSocket()
        teleFlySocket()
        plinkoSocket()
        pvpSocket()
        lotterySocket()
        jackpotSocket()

        // TeleFly is always-on: start the shared round loop on the application scope.
        val teleFlyEngine by inject<TeleFlyEngine>()
        teleFlyEngine.start(this)
        // PvP jackpot wheel is also always-on.
        val pvpEngine by inject<PvpEngine>()
        pvpEngine.start(this)
        // Lottery weekly draw is always-on and persisted; it resumes the active week on boot.
        val lotteryEngine by inject<LotteryEngine>()
        lotteryEngine.start(this)
    }.start(wait = true)
}

private fun initDatabase() {
    val config = HikariConfig().apply {
        jdbcUrl = Env.dbUrl
        username = Env.dbUser
        password = Env.dbPassword
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = Env.dbPoolSize
    }
    // No transaction auto-retry: our money-movement transactions make external
    // operator calls (debit/credit) that must never be replayed.
    Database.connect(
        HikariDataSource(config),
        databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 }
    )

    transaction {
        SchemaUtils.create(
            BrandTable,
            PlayerTable,
            SessionTable,
            RoundTable,
            TransactionTable,
            MinesGameTable,
            ShellGameTable,
            TeleFlyGameTable,
            PlinkoGameTable,
            PvpGameTable,
            LotteryTable,
            LotteryTicketTable
        )
        // Partial unique index Exposed cannot express: one active round per key —
        // EXCEPT lottery, where each ticket is its own round and a player may hold
        // many open rounds at once (capped by LOTTERY_MAX_TICKETS_PER_PLAYER). Recreate
        // idempotently so existing deployments migrate the predicate at boot.
        exec("DROP INDEX IF EXISTS uniq_active_round")
        exec(
            "CREATE UNIQUE INDEX IF NOT EXISTS uniq_active_round " +
                "ON rounds (player_id, game, currency) WHERE closed_at IS NULL AND game <> 'lottery'"
        )
        // Multi-ticket lottery: drop the old one-ticket-per-player uniqueness. Exposed's
        // `uniqueIndex(lottery, player)` is a UNIQUE CONSTRAINT, whose backing index cannot
        // be removed with DROP INDEX (Postgres errors: "...requires it"), so the CONSTRAINT
        // must be dropped (that drops its index too). We match by shape (any 2-column unique
        // on lottery_tickets — the round index is single-column) so we don't depend on
        // Exposed's generated name, dropping the constraint first, then any bare index.
        exec(
            """
            DO ${'$'}${'$'}
            DECLARE r record;
            BEGIN
              FOR r IN
                SELECT con.conname AS name
                FROM pg_constraint con
                JOIN pg_class t ON t.oid = con.conrelid
                WHERE t.relname = 'lottery_tickets' AND con.contype = 'u'
                  AND array_length(con.conkey, 1) = 2
              LOOP
                EXECUTE 'ALTER TABLE lottery_tickets DROP CONSTRAINT IF EXISTS ' || quote_ident(r.name);
              END LOOP;
              FOR r IN
                SELECT c.relname AS name
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_class t ON t.oid = i.indrelid
                WHERE t.relname = 'lottery_tickets' AND i.indisunique
                  AND array_length(i.indkey, 1) = 2
                  AND NOT EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = i.indexrelid)
              LOOP
                EXECUTE 'DROP INDEX IF EXISTS ' || quote_ident(r.name);
              END LOOP;
            END ${'$'}${'$'};
            """.trimIndent()
        )
        // Provably-fair: the public TON seed, added to games that gained a
        // player-supplied client seed. Idempotent so existing deployments migrate.
        exec("ALTER TABLE mines_games ADD COLUMN IF NOT EXISTS ton_seed varchar(64) NOT NULL DEFAULT ''")
        exec("ALTER TABLE shell_games ADD COLUMN IF NOT EXISTS ton_seed varchar(64) NOT NULL DEFAULT ''")
        // PENDING-transaction pattern: money rows now commit before the operator
        // webhook and carry a status; pre-existing rows were only ever written
        // after operator success, so they backfill as CONFIRMED.
        exec("ALTER TABLE transactions ADD COLUMN IF NOT EXISTS status varchar(16) NOT NULL DEFAULT 'CONFIRMED'")
        // Settle-credit reuse looks up credits by round; Postgres does not index FKs.
        exec("CREATE INDEX IF NOT EXISTS idx_transactions_round ON transactions (round_id)")
        // PvP settle durability: the winner's payout is persisted before the first
        // credit webhook attempt so a crash mid-settle never loses the pot.
        exec("ALTER TABLE pvp_games ADD COLUMN IF NOT EXISTS payout bigint")
    }
}
