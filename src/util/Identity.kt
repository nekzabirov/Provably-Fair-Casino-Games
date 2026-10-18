package util

/** Slug value object, used for Brand.identity and Round.game. */
@JvmInline
value class Identity(val value: String) {
    init {
        require(value.matches(SLUG)) { "Identity must be slug: $value" }
    }

    companion object {
        /** Canonical slug: lowercase alphanumerics joined by single `-` or `_`. */
        private val SLUG = Regex("^[a-z0-9]+(?:[-_][a-z0-9]+)*$")
    }
}
