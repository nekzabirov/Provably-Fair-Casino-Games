package util

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Runtime configuration, read from the environment with sane local defaults. */
object Env {

    val serverPort: Int = env("SERVER_PORT", "8080").toInt()

    val dbUrl: String = env("DB_URL", "jdbc:postgresql://localhost:5432/gameengine")
    val dbUser: String = env("DB_USER", "postgres")
    val dbPassword: String = env("DB_PASSWORD", "postgres")
    val dbPoolSize: Int = env("DB_POOL_SIZE", "10").toInt()

    val redisUrl: String = env("REDIS_URL", "redis://localhost:6379")

    val tonApiUrl: String = env("TON_API_URL", "https://toncenter.com/api/v2")

    val sessionExpireDuration: Duration = env("SESSION_EXPIRE_MINUTES", "1440").toLong().minutes
    val brandTimeout: Duration = env("BRAND_TIMEOUT_MS", "5000").toLong().milliseconds
    val tonBlockCacheTtl: Duration = env("TON_BLOCK_CACHE_SECONDS", "5").toLong().seconds

    // TeleFly (crash) round pacing. The crash-point RTP/instant-bust are math
    // constants in CrashPoint.kt; these are operational tuning only.
    val teleFlyTickInterval: Duration = env("TELEFLY_TICK_MS", "100").toLong().milliseconds
    val teleFlyBettingWindow: Duration = env("TELEFLY_BETTING_MS", "10000").toLong().milliseconds
    val teleFlyCrashPause: Duration = env("TELEFLY_CRASH_PAUSE_MS", "4000").toLong().milliseconds
    val teleFlySendTimeout: Duration = env("TELEFLY_SEND_TIMEOUT_MS", "1000").toLong().milliseconds
    val teleFlyGrowthRate: Double = env("TELEFLY_GROWTH_RATE", "0.07").toDouble()

    // PvP jackpot-wheel round pacing (operational tuning only).
    val pvpTickInterval: Duration = env("PVP_TICK_MS", "100").toLong().milliseconds
    val pvpCountdown: Duration = env("PVP_COUNTDOWN_MS", "10000").toLong().milliseconds
    val pvpSpinDuration: Duration = env("PVP_SPIN_MS", "6000").toLong().milliseconds
    val pvpPayoutPause: Duration = env("PVP_PAYOUT_PAUSE_MS", "4000").toLong().milliseconds
    val pvpSendTimeout: Duration = env("PVP_SEND_TIMEOUT_MS", "1000").toLong().milliseconds
    val pvpMinPlayers: Int = env("PVP_MIN_PLAYERS", "2").toInt()

    // Lottery — shared 2-hour draw (TON only), one pot for all brands. Pool amounts
    // are TON nano (10^9 minor units). A player buys ONE system ticket per draw with
    // 6..36 numbers; a 6-number ticket costs the base price, an n-number ticket costs
    // base × (n − 5)² (quadratic volume pricing, see LotteryEngine.priceOf).
    val lotteryCurrency: String = env("LOTTERY_CURRENCY", "TON")
    val lotteryTicketPrice: Long = env("LOTTERY_TICKET_PRICE_NANO", "10000000000").toLong()      // 10 TON base (6 numbers)
    val lotteryBasePool: Long = env("LOTTERY_BASE_POOL_NANO", "1000000000000").toLong()           // 1000 TON
    val lotteryCommissionPercent: Int = env("LOTTERY_COMMISSION_PERCENT", "10").toInt()
    val lotteryNumbers: Int = env("LOTTERY_NUMBERS", "6").toInt()
    val lotteryMaxPicks: Int = env("LOTTERY_MAX_PICKS", "36").toInt()                             // system ticket cap
    val lotteryRange: Int = env("LOTTERY_RANGE", "49").toInt()                                    // numbers 1..49 (6 of 49)
    val lotteryMaxTicketsPerPlayer: Int = env("LOTTERY_MAX_TICKETS_PER_PLAYER", "1").toInt()      // one ticket per player per draw
    val lotteryTickInterval: Duration = env("LOTTERY_TICK_MS", "1000").toLong().milliseconds
    val lotteryDrawInterval: Duration = env("LOTTERY_DRAW_INTERVAL_MS", "10000").toLong().milliseconds
    val lotterySettlePause: Duration = env("LOTTERY_SETTLE_PAUSE_MS", "10000").toLong().milliseconds
    val lotterySendTimeout: Duration = env("LOTTERY_SEND_TIMEOUT_MS", "1000").toLong().milliseconds
    val lotteryRoundDuration: Duration = env("LOTTERY_ROUND_MS", "7200000").toLong().milliseconds // 2h, draws on UTC boundaries

    private fun env(key: String, default: String): String = System.getenv(key) ?: default
}
