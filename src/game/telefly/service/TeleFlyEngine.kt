package game.telefly.service

import exception.BettingClosedException
import exception.NoActiveBetException
import exception.RoundNotFoundException
import exception.WrongPhaseException
import game.telefly.model.BetPlacedFrame
import game.telefly.model.BetCashoutFrame
import game.telefly.model.CrashFrame
import game.telefly.model.Phase
import game.telefly.model.PhaseFrame
import game.telefly.model.TeleFlyGame
import game.telefly.model.TeleFlyState
import game.telefly.model.TickFrame
import game.telefly.network.ConnectionRegistry
import kotlin.math.exp
import kotlin.math.ln
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.Player
import model.Round
import model.RoundTable
import org.jetbrains.exposed.sql.and
import org.slf4j.LoggerFactory
import service.PlayerService
import service.ProvablyFair
import service.RoundService
import util.Amount
import util.Currency
import util.Env
import util.runTransactionCatching

/**
 * The TeleFly (crash) game — a single, always-on, server-driven round loop shared
 * by every connected player. One coroutine ticks the multiplier in real time and
 * broadcasts it; per-connection bet/cashout commands mutate shared state.
 *
 * Round lifecycle, forever (even with zero players):
 *   BETTING (10s, one bet per player) -> FLYING (multiplier rises 1.00x along
 *   exp(growthRate*t) until the provably-fair crash point) -> CRASHED (~4s pause,
 *   reveal seed, settle losses) -> BETTING -> ...
 *
 * Concurrency: a single [Mutex] guards the in-memory round/bet state. It is NEVER
 * held across an operator HTTP call (debit/credit/close). The pattern is
 * lock -> validate + claim in memory -> unlock -> slow money work -> re-lock to
 * finalize/revert. Money still flows through [RoundService] per player exactly as
 * the other games (one shared [Round] per bet, DEBIT on bet, CREDIT on cashout).
 */
class TeleFlyEngine(
    private val provablyFair: ProvablyFair,
    private val roundService: RoundService,
    private val playerService: PlayerService,
    private val registry: ConnectionRegistry,
    private val json: Json
) {
    private val mutex = Mutex()

    private var round: RoundData? = null
    private val bets = HashMap<Long, BetSlot>()
    private val history = ArrayDeque<Double>()

    private lateinit var scope: CoroutineScope
    private var settlementJob: Job? = null

    /** Last successfully fetched TON client seed — reused if a later fetch fails, so a
     *  transient TON outage degrades (reused public seed) rather than killing the loop. */
    private var lastClientSeed: String? = null

    /** The next round's seeds, prepared off-lock during the crash pause so the
     *  blocking TON client seed fetch never happens while holding the mutex. */
    private var pending: PreparedRound? = null

    // ── lifecycle ────────────────────────────────────────────────────────────

    /** Start the engine. Reconciles orphaned rounds, opens the first round, loops. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            reconcile()
            // Boot needs a real client seed; retry until TON is reachable.
            var prep: PreparedRound? = null
            while (prep == null && scope.isActive) {
                prep = try {
                    prepareRound(0L)
                } catch (e: Exception) {
                    log.error("initial round prepare failed; retrying", e)
                    delay(BOOT_RETRY_MS)
                    null
                }
            }
            if (prep == null) return@launch
            val frame = mutex.withLock {
                installBetting(prep, Clock.System.now())
                phaseFrameTextLocked()
            }
            registry.broadcast(frame)
            loop()
        }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            try {
                tick()
            } catch (e: Exception) {
                log.error("TeleFly tick failed", e)
            }
            delay(Env.teleFlyTickInterval)
        }
    }

    private suspend fun tick() {
        val now = Clock.System.now()
        val out = mutex.withLock {
            val r = round ?: return
            when (r.phase) {
                Phase.BETTING ->
                    if (now >= r.phaseEndsAt) {
                        toFlying(r, now)
                        Out.Send(phaseFrameTextLocked())
                    } else Out.Idle

                Phase.FLYING -> {
                    r.currentMultiplier = multiplierAt(now - r.phaseStartedAt, r.crashMultiplier)
                    if (now >= r.phaseEndsAt) {
                        val losers = toCrashed(r, now)
                        Out.Crash(
                            json.encodeToString(CrashFrame(r.crashMultiplier, r.serverSeed, r.clientSeed, r.roundNonce)),
                            losers,
                            r.roundNonce
                        )
                    } else {
                        Out.Send(json.encodeToString(TickFrame(r.currentMultiplier)))
                    }
                }

                Phase.CRASHED ->
                    if (now >= r.phaseEndsAt && settlementDone() && pending != null) {
                        installBetting(pending!!, now)
                        pending = null
                        Out.Send(phaseFrameTextLocked())
                    } else Out.Idle
            }
        }

        when (out) {
            Out.Idle -> {}
            is Out.Send -> registry.broadcast(out.text)
            is Out.Crash -> {
                registry.broadcast(out.text)
                settlementJob = scope.launch { settleLosers(out.losers) }
                // Off-lock: blocking TON fetch is safe here. Resilient — falls back to
                // the last client seed, so this won't throw once we've booted.
                pending = try {
                    prepareRound(out.nonce)
                } catch (e: Exception) {
                    log.error("preparing next round failed", e)
                    null
                }
            }
        }
    }

    private fun settlementDone(): Boolean = settlementJob?.isCompleted != false

    // ── phase transitions (all called under the lock) ─────────────────────────

    private fun installBetting(prep: PreparedRound, now: Instant) {
        round = RoundData(
            phase = Phase.BETTING,
            roundNonce = prep.nonce,
            serverSeed = prep.serverSeed,
            serverSeedHash = prep.serverSeedHash,
            clientSeed = prep.clientSeed,
            crashMultiplier = prep.crash,
            phaseStartedAt = now,
            phaseEndsAt = now + Env.teleFlyBettingWindow,
            currentMultiplier = 1.0
        )
        bets.clear()
    }

    private fun toFlying(r: RoundData, now: Instant) {
        r.phase = Phase.FLYING
        r.phaseStartedAt = now
        r.phaseEndsAt = now + flightDuration(r.crashMultiplier)
        r.currentMultiplier = 1.0
    }

    private fun toCrashed(r: RoundData, now: Instant): List<LoserRef> {
        r.phase = Phase.CRASHED
        r.currentMultiplier = r.crashMultiplier
        r.phaseStartedAt = now
        r.phaseEndsAt = now + Env.teleFlyCrashPause

        val losers = bets.values
            .filter { it.state == SlotState.ACTIVE }
            .map { LoserRef(it.playerId, it.sessionToken, it.currency) }
        bets.values.forEach { if (it.state == SlotState.ACTIVE) it.state = SlotState.BUST }

        history.addLast(r.crashMultiplier)
        while (history.size > HISTORY_MAX) history.removeFirst()
        return losers
    }

    // ── auth snapshot ─────────────────────────────────────────────────────────

    /** The current state for [player], used as the auth reply (restores an active bet on reconnect). */
    suspend fun snapshotFor(player: Player, token: String, currency: Currency): TeleFlyState {
        val view = mutex.withLock {
            val r = round ?: return@withLock LockedView(Phase.CRASHED, "", 1.0, 0L, 0, null, emptyList(), null)
            val slot = bets[player.id.value]
            LockedView(
                r.phase, r.serverSeedHash, r.currentMultiplier, r.phaseEndsAt.toEpochMilliseconds(),
                bets.size, slot?.let(::toBetView), history.toList(), slot?.balance
            )
        }
        // findBalance reads the lazy player.brand relation, so it needs a transaction
        // (the slot path already has a cached balance and skips the operator call).
        val balance = view.slotBalance ?: runTransactionCatching {
            playerService.findBalance(player, token, currency).getOrThrow()
        }.getOrThrow()
        return TeleFlyState(
            player = TeleFlyState.PlayerInfo.from(player, balance),
            phase = view.phase,
            serverSeedHash = view.hash,
            multiplier = view.multiplier,
            phaseEndsAt = view.phaseEndsAt,
            players = view.players,
            bet = view.bet,
            history = view.history
        )
    }

    // ── bet ─────────────────────────────────────────────────────────────────

    /** Place the player's single bet for the current round (BETTING only). Idempotent. */
    suspend fun placeBet(player: Player, token: String, currency: Currency, amount: Long): TeleFlyState {
        require(amount > 0) { "amount must be positive" }
        val playerId = player.id.value
        val now = Clock.System.now()

        val step = mutex.withLock {
            val r = round ?: throw WrongPhaseException()
            if (r.phase != Phase.BETTING) throw BettingClosedException()
            bets[playerId]?.let { return@withLock BetStep.Idempotent(stateLocked(player, it)) }
            // Don't start a debit that can't finish before takeoff.
            if (r.phaseEndsAt - now < Env.brandTimeout) throw BettingClosedException()
            bets[playerId] = BetSlot(
                playerId = playerId, sessionToken = token, currency = currency,
                username = player.username, profilePic = player.profilePic,
                roundId = 0L, placeAmount = Amount(amount), balance = Amount(0),
                cashoutMultiplier = null, state = SlotState.PLACING
            )
            BetStep.Reserved(r.roundNonce, r.serverSeed, r.clientSeed, r.crashMultiplier)
        }
        if (step is BetStep.Idempotent) return step.state
        val reserved = step as BetStep.Reserved

        val result = runCatching {
            // The round commits BEFORE the stake moves: the debit runs in its own
            // transactions and must reference a durable round.
            val round = runTransactionCatching {
                val round = roundService.open(player, token, GAME, currency).getOrThrow()
                // A stale adopted round still carrying a game payload belongs to an
                // earlier bet; debiting into it would tangle two bets' money history.
                check(TeleFlyGame.forRound(round) == null) { "previous round still settling" }
                round
            }.getOrThrow()

            val debit = roundService.debit(player, token, GAME, currency, Amount(amount)).getOrThrow()

            runTransactionCatching {
                val balance = playerService.findBalance(player, token, currency).getOrThrow()
                TeleFlyGame.new {
                    this.round = round
                    this.roundNonce = reserved.nonce
                    this.sessionToken = token
                    this.placeAmount = Amount(amount)
                    this.balance = balance
                    this.serverSeed = reserved.serverSeed
                    this.clientSeed = reserved.clientSeed
                    this.crashMultiplier = reserved.crash
                }
                round.id.value to balance
            }.getOrElse { e ->
                // The operator took the stake but the bet could not be recorded: refund + void.
                roundService.compensateDebit(player, token, GAME, currency, debit)
                throw e
            }
        }

        var playersCount = 0
        val state = mutex.withLock {
            result.fold(
                onSuccess = { (roundId, balance) ->
                    val slot = bets[playerId] ?: throw WrongPhaseException()
                    slot.roundId = roundId
                    slot.balance = balance
                    slot.state = SlotState.ACTIVE
                    playersCount = bets.size
                    stateLocked(player, slot)
                },
                onFailure = { e ->
                    bets.remove(playerId)
                    throw e
                }
            )
        }
        registry.broadcast(
            json.encodeToString(
                BetPlacedFrame(player.username, amount, currency.value, playersCount, player.profilePic)
            )
        )
        return state
    }

    // ── cashout ───────────────────────────────────────────────────────────────

    /** Cash out the player's active bet at the engine's authoritative current multiplier. */
    suspend fun cashout(player: Player, token: String, currency: Currency): TeleFlyState {
        val playerId = player.id.value
        val now = Clock.System.now()

        val claim = mutex.withLock {
            val r = round ?: throw WrongPhaseException()
            if (r.phase != Phase.FLYING) throw WrongPhaseException()
            val slot = bets[playerId] ?: throw NoActiveBetException()
            if (slot.state != SlotState.ACTIVE) throw WrongPhaseException()
            val m = multiplierAt(now - r.phaseStartedAt, r.crashMultiplier)
            slot.state = SlotState.CASHING_OUT
            slot.cashoutMultiplier = m
            Claim(slot.roundId, m, slot.placeAmount)
        }

        val payout = Amount((claim.placeAmount.value * claim.multiplier).toLong())
        val result = runCatching {
            // Credit first, in its own flow: a lost webhook leaves a PENDING row
            // whose id a retried cashout replays — the operator deduplicates
            // instead of paying twice.
            roundService.credit(player, token, GAME, currency, payout).getOrThrow()

            runTransactionCatching {
                val round = Round.findById(claim.roundId) ?: throw RoundNotFoundException()
                roundService.close(player, token, GAME, currency).getOrThrow()
                TeleFlyGame.forRound(round)?.cashoutMultiplier = claim.multiplier
                playerService.findBalance(player, token, currency).getOrThrow()
            }.getOrThrow()
        }

        val outcome = mutex.withLock {
            val slot = bets[playerId]
            result.fold(
                onSuccess = { balance ->
                    slot?.let { it.state = SlotState.CASHED; it.balance = balance }
                    CashOutcome.Ok(stateLocked(player, slot))
                },
                onFailure = { e ->
                    var lossClose = false
                    slot?.let {
                        if (it.state == SlotState.CASHING_OUT) {
                            if (round?.phase == Phase.FLYING) {
                                it.state = SlotState.ACTIVE      // crash settlement will catch it, or retry
                                it.cashoutMultiplier = null
                            } else {
                                it.state = SlotState.BUST        // crash already settled and skipped us
                                it.cashoutMultiplier = null
                                lossClose = true
                            }
                        }
                    }
                    CashOutcome.Err(e, lossClose)
                }
            )
        }

        return when (outcome) {
            is CashOutcome.Ok -> {
                registry.broadcast(
                    json.encodeToString(
                        BetCashoutFrame(player.username, claim.multiplier, payout.value, currency.value, player.profilePic)
                    )
                )
                outcome.state
            }
            is CashOutcome.Err -> {
                if (outcome.lossClose) {
                    scope.launch { settleLosers(listOf(LoserRef(playerId, token, currency))) }
                }
                throw outcome.error
            }
        }
    }

    // ── settlement & reconciliation (off the tick path, one tx per player) ─────

    private suspend fun settleLosers(losers: List<LoserRef>) {
        for (l in losers) {
            runTransactionCatching {
                val player = Player.findById(l.playerId) ?: return@runTransactionCatching
                roundService.close(player, l.sessionToken, GAME, l.currency).getOrThrow()
            }.onFailure { log.error("settle-loss failed for player ${l.playerId}", it) }
        }
    }

    /**
     * On startup, void any open `telefly` rounds left by a previous crash/restart:
     * refund the stake and close. The server (not the player) failed and we have no
     * trustworthy record of who cashed out, so a net-zero refund is the defensible
     * outcome — and closing frees the uniq_active_round index for the next bet.
     */
    private suspend fun reconcile() {
        val orphanIds = runTransactionCatching {
            Round.find { (RoundTable.game eq GAME.value) and RoundTable.closedAt.isNull() }
                .map { it.id.value }
        }.getOrElse {
            log.error("reconcile: failed to load orphan rounds", it)
            emptyList()
        }

        for (roundId in orphanIds) {
            val refund = runTransactionCatching {
                val round = Round.findById(roundId) ?: return@runTransactionCatching null
                val game = TeleFlyGame.forRound(round)
                if (game == null) {
                    round.closedAt = Clock.System.now()       // no audit row; just free the index
                    return@runTransactionCatching null
                }
                Refund(round.player, game.sessionToken, round.currency, game.placeAmount)
            }.getOrElse {
                log.error("reconcile orphan round $roundId failed", it)
                null
            } ?: continue

            runCatching {
                // Reuse-aware credit: a PENDING cashout left by the crash replays
                // with its original id, and an already CONFIRMED one is never paid
                // again — the refund only flows when no credit ever landed.
                roundService.credit(refund.player, refund.sessionToken, GAME, refund.currency, refund.stake).getOrThrow()
                roundService.close(refund.player, refund.sessionToken, GAME, refund.currency).getOrThrow()
            }.onFailure { log.error("reconcile orphan round $roundId failed", it) }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun prepareRound(prevNonce: Long): PreparedRound {
        val serverSeed = provablyFair.generateServerSeed()
        val serverSeedHash = provablyFair.hashServerSeed(serverSeed)
        val clientSeed = try {
            provablyFair.getTonSeed().also { lastClientSeed = it }   // synchronous TON fetch, off-lock
        } catch (e: Exception) {
            lastClientSeed?.also { log.warn("getClientSeed failed; reusing last client seed", e) } ?: throw e
        }
        val nonce = prevNonce + 1
        val crash = CrashPoint.calculate(provablyFair.computeHash(serverSeed, clientSeed, nonce.toString()))
        return PreparedRound(nonce, serverSeed, serverSeedHash, clientSeed, crash)
    }

    /** Build the personal state from an already-known slot (no operator call). */
    private fun stateLocked(player: Player, slot: BetSlot?): TeleFlyState {
        val r = round!!
        val balance = slot?.balance ?: Amount(0)
        return TeleFlyState(
            player = TeleFlyState.PlayerInfo.from(player, balance),
            phase = r.phase,
            serverSeedHash = r.serverSeedHash,
            multiplier = r.currentMultiplier,
            phaseEndsAt = r.phaseEndsAt.toEpochMilliseconds(),
            players = bets.size,
            bet = slot?.let(::toBetView),
            history = history.toList()
        )
    }

    private fun phaseFrameTextLocked(): String {
        val r = round!!
        return json.encodeToString(
            PhaseFrame(
                r.phase, r.roundNonce, r.serverSeedHash, r.phaseEndsAt.toEpochMilliseconds(),
                bets.size, history.toList()
            )
        )
    }

    private fun toBetView(slot: BetSlot): TeleFlyState.BetView {
        val status = when (slot.state) {
            SlotState.CASHED -> TeleFlyState.BetStatus.CASHED
            SlotState.BUST -> TeleFlyState.BetStatus.LOST
            else -> TeleFlyState.BetStatus.PLACED
        }
        val settle = slot.cashoutMultiplier?.let { (slot.placeAmount.value * it).toLong() } ?: 0L
        return TeleFlyState.BetView(slot.placeAmount.value, slot.cashoutMultiplier, settle, status)
    }

    private fun multiplierAt(elapsed: Duration, crash: Double): Double =
        minOf(crash, exp(Env.teleFlyGrowthRate * elapsed.toDouble(DurationUnit.SECONDS)))

    private fun flightDuration(crash: Double): Duration {
        if (crash <= 1.0) return Duration.ZERO
        return (ln(crash) / Env.teleFlyGrowthRate * 1000.0).toLong().milliseconds
    }

    // ── in-memory types ─────────────────────────────────────────────────────────

    private class RoundData(
        var phase: Phase,
        val roundNonce: Long,
        val serverSeed: String,
        val serverSeedHash: String,
        val clientSeed: String,
        val crashMultiplier: Double,
        var phaseStartedAt: Instant,
        var phaseEndsAt: Instant,
        var currentMultiplier: Double
    )

    private enum class SlotState { PLACING, ACTIVE, CASHING_OUT, CASHED, BUST }

    private class BetSlot(
        val playerId: Long,
        val sessionToken: String,
        val currency: Currency,
        val username: String,
        val profilePic: String?,
        var roundId: Long,
        var placeAmount: Amount,
        var balance: Amount,
        var cashoutMultiplier: Double?,
        var state: SlotState
    )

    private class PreparedRound(
        val nonce: Long,
        val serverSeed: String,
        val serverSeedHash: String,
        val clientSeed: String,
        val crash: Double
    )

    private class LoserRef(val playerId: Long, val sessionToken: String, val currency: Currency)

    private class Refund(val player: Player, val sessionToken: String, val currency: Currency, val stake: Amount)

    private class LockedView(
        val phase: Phase,
        val hash: String,
        val multiplier: Double,
        val phaseEndsAt: Long,
        val players: Int,
        val bet: TeleFlyState.BetView?,
        val history: List<Double>,
        val slotBalance: Amount?
    )

    private class Claim(val roundId: Long, val multiplier: Double, val placeAmount: Amount)

    private sealed interface BetStep {
        class Idempotent(val state: TeleFlyState) : BetStep
        class Reserved(val nonce: Long, val serverSeed: String, val clientSeed: String, val crash: Double) : BetStep
    }

    private sealed interface Out {
        data object Idle : Out
        class Send(val text: String) : Out
        class Crash(val text: String, val losers: List<LoserRef>, val nonce: Long) : Out
    }

    private sealed interface CashOutcome {
        class Ok(val state: TeleFlyState) : CashOutcome
        class Err(val error: Throwable, val lossClose: Boolean) : CashOutcome
    }

    private companion object {
        val GAME = TeleFlyGame.GAME
        const val HISTORY_MAX = 25
        const val BOOT_RETRY_MS = 2000L
        private val log = LoggerFactory.getLogger(TeleFlyEngine::class.java)
    }
}
