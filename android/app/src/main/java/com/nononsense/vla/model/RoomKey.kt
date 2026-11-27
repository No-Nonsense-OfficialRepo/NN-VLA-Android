package com.nononsense.vla.model

@JvmInline
value class RoomKey private constructor(val value: String) {
    val displayValue: String get() = value.chunked(4).joinToString("-")

    companion object {
        private val validKey = Regex("^[0-9a-f]{16}$")

        fun parse(input: String): Result<RoomKey> {
            val normalized = input.trim().lowercase().replace("-", "").replace(" ", "")
            return if (validKey.matches(normalized)) {
                Result.success(RoomKey(normalized))
            } else {
                Result.failure(IllegalArgumentException("Room keys contain 16 hexadecimal characters."))
            }
        }
    }
}
