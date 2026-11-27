package com.nononsense.vla.admission

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Canonical JSON used by the desktop Host's Ed25519 signing code. */
internal object SignedRoomJson {
    private val decoder = Base64.getDecoder()
    private val encoder = Base64.getEncoder()
    private val unicodeOrder = Comparator<String> { first, second ->
        val a = first.codePoints().iterator()
        val b = second.codePoints().iterator()
        while (a.hasNext() && b.hasNext()) {
            val difference = a.nextInt().compareTo(b.nextInt())
            if (difference != 0) return@Comparator difference
        }
        a.hasNext().compareTo(b.hasNext())
    }

    fun base64(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun unbase64(value: String): ByteArray = decoder.decode(value)

    fun verify(fields: JSONObject, publicKey: String): Boolean {
        val signature = fields.opt("sig") as? String ?: return false
        return try {
            val rawKey = unbase64(publicKey)
            val rawSignature = unbase64(signature)
            if (rawKey.size != Ed25519PublicKeyParameters.KEY_SIZE || rawSignature.size != 64) return false
            val data = canonical(fields, omitSignature = true)
            Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(rawKey))
                update(data, 0, data.size)
            }.verifySignature(rawSignature)
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    fun sign(fields: JSONObject, privateKey: Ed25519PrivateKeyParameters): String {
        val data = canonical(fields, omitSignature = true)
        return base64(Ed25519Signer().apply {
            init(true, privateKey)
            update(data, 0, data.size)
        }.generateSignature())
    }

    private fun canonical(fields: JSONObject, omitSignature: Boolean) = buildString {
        appendObject(fields, omitSignature)
    }.toByteArray(Charsets.US_ASCII)

    private fun StringBuilder.appendObject(fields: JSONObject, omitSignature: Boolean = false) {
        val keys = fields.keys().asSequence()
            .filterNot { omitSignature && it == "sig" }
            .sortedWith(unicodeOrder)
        append('{')
        keys.forEachIndexed { index, key ->
            if (index != 0) append(',')
            appendString(key)
            append(':')
            appendValue(fields.get(key))
        }
        append('}')
    }

    private fun StringBuilder.appendValue(value: Any?) {
        when {
            value == null || value === JSONObject.NULL -> append("null")
            value is String -> appendString(value)
            value is Int || value is Long || value is Boolean -> append(value.toString())
            value is JSONObject -> appendObject(value)
            value is JSONArray -> {
                append('[')
                for (index in 0 until value.length()) {
                    if (index != 0) append(',')
                    appendValue(value.get(index))
                }
                append(']')
            }
            else -> throw IllegalArgumentException("Unsupported signed room JSON value")
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                in ' '..'~' -> append(character)
                else -> append("\\u").append(character.code.toString(16).padStart(4, '0'))
            }
        }
        append('"')
    }
}
