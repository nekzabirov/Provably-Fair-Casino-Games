package api

import game.lottery.model.LotteryTicket
import game.mines.model.MinesGame
import game.plinko.model.PlinkoGame
import game.pvp.model.PvpGame
import game.shell.model.ShellGame
import game.telefly.model.TeleFlyGame

/** The static list of games we expose. Each entry references the game's own identity constant. */
object GameCatalog {
    val all: List<GameDto> = listOf(
        GameDto(MinesGame.GAME.value),
        GameDto(ShellGame.GAME.value),
        GameDto(TeleFlyGame.GAME.value),
        GameDto(PlinkoGame.GAME.value),
        GameDto(PvpGame.GAME.value),
        GameDto(LotteryTicket.GAME.value)
    )
}
