package game.lottery.service

import kotlinx.datetime.Instant
import util.Env

/**
 * Draw boundary. The lottery runs in fixed rounds ([Env.lotteryRoundDuration], 2h by
 * default) aligned to UTC epoch multiples, so draws land on predictable wall-clock
 * boundaries (00:00, 02:00, 04:00, …). Pure given [now] (no clock read), so it stays
 * deterministic and the engine controls when "now" is sampled.
 */
object LotterySchedule {

    /** The next draw boundary strictly after [now]: the next UTC multiple of the round duration. */
    fun nextDrawAfter(now: Instant): Instant {
        val period = Env.lotteryRoundDuration.inWholeMilliseconds
        val next = (now.toEpochMilliseconds() / period + 1) * period
        return Instant.fromEpochMilliseconds(next)
    }
}
