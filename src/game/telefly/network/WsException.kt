package game.telefly.network

/** Transport-level errors (distinct from domain GameException). Surfaced as an error frame. */
sealed class WsException(message: String) : Exception(message)

class MalformedFrameException : WsException("Malformed frame")

class UnknownCommandException(type: String) : WsException("Unknown command: $type")

class NotAuthenticatedException : WsException("Not authenticated")
