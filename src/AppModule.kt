import adapter.BrandAdapter
import adapter.CacheAdapter
import adapter.TonAdapter
import game.mines.command.MinesAuthHandler
import game.mines.command.MinesCashoutHandler
import game.mines.command.MinesOpenHandler
import game.mines.command.MinesStartHandler
import game.mines.network.ConnectionRegistry
import game.plinko.command.PlinkoAuthHandler
import game.plinko.command.PlinkoDropHandler
import game.lottery.command.LotteryAuthHandler
import game.lottery.command.LotteryBuyHandler
import game.lottery.service.LotteryEngine
import game.pvp.command.PvpAuthHandler
import game.pvp.command.PvpBetHandler
import game.pvp.command.PvpCancelHandler
import game.pvp.service.PvpEngine
import game.shell.command.ShellAuthHandler
import game.shell.command.ShellPickHandler
import game.shell.command.ShellStartHandler
import game.telefly.command.TeleFlyAuthHandler
import game.telefly.command.TeleFlyBetHandler
import game.telefly.command.TeleFlyCashoutHandler
import game.telefly.service.TeleFlyEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.api.coroutines
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import service.BrandService
import service.PlayerService
import service.ProvablyFair
import service.RoundService
import util.Env

/** Koin wiring. No interfaces — concrete classes bound directly. */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
val appModule = module {
    single { httpClient() }
    single { redisCommands() }
    single { Json { ignoreUnknownKeys = true; encodeDefaults = true } }

    single { CacheAdapter(get()) }
    single { BrandAdapter(get()) }
    single { TonAdapter() }

    single { ProvablyFair(get()) }
    single { BrandService() }
    single { PlayerService(get(), get()) }
    single { RoundService(get()) }

    single { ConnectionRegistry() }

    single { MinesAuthHandler(get(), get(), get(), get()) }
    single { MinesStartHandler(get(), get(), get()) }
    single { MinesOpenHandler(get(), get()) }
    single { MinesCashoutHandler(get(), get()) }

    // Shell game — its own self-contained network registry (distinct type from Mines').
    single { game.shell.network.ConnectionRegistry() }
    single { ShellAuthHandler(get(), get(), get(), get()) }
    single { ShellStartHandler(get(), get(), get()) }
    single { ShellPickHandler(get(), get(), get()) }

    // Plinko — instant drops; its own self-contained network registry.
    single { game.plinko.network.ConnectionRegistry() }
    single { PlinkoAuthHandler(get(), get(), get()) }
    single { PlinkoDropHandler(get(), get(), get()) }

    // TeleFly (crash) — its own registry; the engine owns the shared real-time round loop.
    single { game.telefly.network.ConnectionRegistry() }
    single { TeleFlyEngine(get(), get(), get(), get(), get()) }
    single { TeleFlyAuthHandler(get(), get(), get()) }
    single { TeleFlyBetHandler(get()) }
    single { TeleFlyCashoutHandler(get()) }

    // PvP jackpot wheel — its own registry; the engine owns the shared real-time round loop.
    single { game.pvp.network.ConnectionRegistry() }
    single { PvpEngine(get(), get(), get(), get(), get()) }
    single { PvpAuthHandler(get(), get(), get()) }
    single { PvpBetHandler(get()) }
    single { PvpCancelHandler(get()) }

    // Lottery — weekly shared draw (TON only); the engine owns the persistent weekly loop.
    // ConnectionRegistry = per-player snapshots; JackpotRegistry = public /ws/jackpot fan-out.
    single { game.lottery.network.ConnectionRegistry() }
    single { game.lottery.network.JackpotRegistry() }
    single { LotteryEngine(get(), get(), get(), get(), get()) }
    single { LotteryAuthHandler(get(), get(), get()) }
    single { LotteryBuyHandler(get()) }
}

private fun httpClient(): HttpClient = HttpClient(CIO) {
    expectSuccess = true
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

@OptIn(ExperimentalLettuceCoroutinesApi::class)
private fun redisCommands(): RedisCoroutinesCommands<String, String> =
    RedisClient.create(Env.redisUrl).connect().coroutines()
