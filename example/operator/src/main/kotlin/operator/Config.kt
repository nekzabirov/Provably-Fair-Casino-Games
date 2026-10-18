package operator

/** Demo operator configuration. Identity/secret must match the brand row in the engine. */
object Config {
    val port: Int = env("OPERATOR_PORT", "9090").toInt()

    /** Sent to the engine as X-Operator — the brand identity registered there. */
    val identity: String = env("OPERATOR_IDENTITY", "demo-operator")

    /** Shared secret: sent to the engine as X-Secret-Key, and required on incoming webhooks. */
    val secret: String = env("OPERATOR_SECRET", "demo-secret")

    /** Base URL of the game engine. */
    val engineUrl: String = env("ENGINE_URL", "http://localhost:8080")

    /** Balance handed to a player on first contact (minor units). */
    val startingBalance: Long = env("OPERATOR_START_BALANCE", "1000000").toLong()

    private fun env(key: String, default: String): String = System.getenv(key) ?: default
}
