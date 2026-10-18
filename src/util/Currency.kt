package util

/** Fiat or crypto currency code (USD, EUR, TON, USDT, BTC, ...). */
@JvmInline
value class Currency(val value: String) {
    init {
        require(value.matches(Regex("^[A-Z]{3,10}$"))) { "Invalid currency: $value" }
    }

    companion object {
        /** The single currency every game is played in. Stars (XTR) were removed. */
        val TON = Currency("TON")
    }
}
