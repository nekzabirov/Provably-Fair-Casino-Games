package game.lottery.command

import game.lottery.network.Connection
import game.lottery.service.LotteryEngine
import kotlinx.serialization.Serializable

/** buy — purchase this draw's single system ticket with 6..36 distinct numbers. */
@Serializable
data class LotteryBuy(val numbers: List<Int>)

class LotteryBuyHandler(private val engine: LotteryEngine) {
    suspend fun handle(connection: Connection, command: LotteryBuy) {
        val player = connection.requirePlayer()
        val currency = connection.requireCurrency()
        val token = connection.requireToken()

        val state = engine.buy(player, token, currency, command.numbers)
        connection.send(state)
    }
}
