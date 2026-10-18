package exception

/** Root of all domain errors. Thrown inside runTransactionCatching, surfaces as Result.failure. */
sealed class GameException(message: String) : Exception(message)

class SessionNotFoundException : GameException("Session not found")

class SessionExpiredException : GameException("Session expired")

class RoundNotFoundException : GameException("Active round not found")

class RoundAlreadyClosedException : GameException("Round already closed")

/** The operator declined a webhook call (e.g. insufficient funds, rejected debit). */
class OperatorRejectedException(message: String) : GameException(message)

/** TeleFly: a bet arrived outside the betting window. */
class BettingClosedException : GameException("Betting is closed")

/** TeleFly: a command arrived in the wrong round phase (e.g. cashout while not flying). */
class WrongPhaseException : GameException("Action not allowed in this phase")

/** TeleFly: the player already has a bet this round (single bet per round). */
class BetAlreadyPlacedException : GameException("Bet already placed this round")

/** TeleFly: cashout with no active bet to cash out. */
class NoActiveBetException : GameException("No active bet")

/** Lottery: the player already holds a ticket for the current draw (one per draw). */
class TicketAlreadyBoughtException : GameException("Ticket already bought for this lottery")

/** Lottery: the player reached the per-draw ticket cap (each ticket is its own round). */
class TicketLimitReachedException(max: Int) : GameException("Ticket limit reached: max $max tickets per draw")
