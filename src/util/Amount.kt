package util

/** Money in minor units. Never Double, never BigDecimal. */
@JvmInline
value class Amount(val value: Long) {
    init {
        require(value >= 0) { "Amount cannot be negative: $value" }
    }
}
