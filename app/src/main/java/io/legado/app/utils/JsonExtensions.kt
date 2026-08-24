package io.legado.app.utils

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.ToNumberPolicy
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.ParseContext
import com.jayway.jsonpath.ReadContext
import io.legado.app.exception.NoStackTraceException
import java.math.BigDecimal

val jsonPath: ParseContext by lazy {
    JsonPath.using(
        Configuration.builder()
            .options(Option.SUPPRESS_EXCEPTIONS)
            .build()
    )
}

fun ReadContext.readString(path: String): String? = this.read(path, String::class.java)

fun ReadContext.readBool(path: String): Boolean? = this.read(path, Boolean::class.java)

fun ReadContext.readInt(path: String): Int? = this.read(path, Int::class.java)

fun ReadContext.readLong(path: String): Long? = this.read(path, Long::class.java)

private val Gson by lazy {
    GsonBuilder().disableHtmlEscaping()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .serializeNulls()
        .create()
}

private fun Number.toJsonString(): String = when(this) {
    is Long, is Int, is Short, is Byte -> toString()
    is Double -> {
        if (this % 1.0 == 0.0) toLong().toString()
        else BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
    }
    else -> BigDecimal.valueOf(toDouble()).stripTrailingZeros().toPlainString()
}

fun toJsonString(raw: Any?): String = when (raw) {
    null -> "null"
    is Boolean -> raw.toString()
    is Number -> raw.toJsonString()
    is String -> raw
    is CharSequence -> raw.toString()
    is Map<*, *> -> Gson.toJson(toAnyWrapper(raw))
    is List<*> -> Gson.toJson(toAnyWrapper(raw))
    is Array<*> -> Gson.toJson(toAnyWrapper(raw))
    is JsonElement -> Gson.toJson(raw)
    else -> try {
        Gson.toJson(raw)
    } catch (e: Exception) {
        raw.toString()
    }
}

fun toAnyWrapper(raw: Any?): Any? = when (raw) {
    null -> null
    is Boolean -> raw
    is Number -> if (raw is Double && raw % 1.0 == 0.0) raw.toLong() else raw
    is String -> raw
    is CharSequence -> raw.toString()
    is Map<*, *> -> raw.entries.associate {it.key.toString() to toAnyWrapper(it.value) }
    is List<*> -> raw.map { toAnyWrapper(it) }
    is Array<*> -> raw.map { toAnyWrapper(it) }
    is JsonElement -> when {
        raw.isJsonNull -> null
        raw.isJsonObject -> raw.asJsonObject.entrySet().associate {
            it.key to toAnyWrapper(it.value)
        }
        raw.isJsonArray -> raw.asJsonArray.map { toAnyWrapper(it) }
        raw.isJsonPrimitive -> with(raw.asJsonPrimitive) {
            when {
                isBoolean -> asBoolean
                isNumber -> asNumber.let {
                    if (it is Double && it % 1.0 == 0.0) it.toLong() else it
                }
                isString -> asString
                else -> raw
            }
        }
        else -> raw
    }
    else -> raw
}

inline fun <T> parseToMapImpl(
    raw: Any?,
    valueMapper: (Any?) -> T
): Map<String, T> {
    return try {
        when {
            raw == null -> emptyMap()
            raw is Map<*, *> -> {
                if (raw.isEmpty()) return emptyMap()
                raw.entries.associate { it.key.toString() to valueMapper(it.value) }
            }
            raw is JsonElement && raw.isJsonObject -> {
                raw.asJsonObject.entrySet().associate { it.key to valueMapper(it.value) }
            }
            raw is CharSequence -> {
                if (raw.isBlank()) return emptyMap()
                val json = JsonParser.parseString(raw.toString())
                when {
                    json.isJsonObject -> json.asJsonObject.entrySet()
                        .associate { it.key to valueMapper(it.value) }
                    else -> emptyMap()
                }
            }
            else -> throw NoStackTraceException("不支持的类型: ${raw?.javaClass?.name.orEmpty()}")
        }
    } catch (e: Exception) {
        throw NoStackTraceException("parseToMap转换失败，${e.message}")
    }
}

inline fun <T> parseToListImpl(
    raw: Any?,
    valueMapper: (Any?) -> T
): List<T> {
    return try {
        when {
            raw == null -> emptyList()
            raw is List<*> -> {
                if (raw.isEmpty()) return emptyList()
                raw.map { valueMapper(it) }
            }
            raw is Array<*> -> {
                if (raw.isEmpty()) return emptyList()
                raw.map { valueMapper(it) }
            }
            raw is JsonElement && raw.isJsonArray -> raw.asJsonArray.map { valueMapper(it) }
            raw is CharSequence -> {
                if (raw.isBlank()) return emptyList()
                val json = JsonParser.parseString(raw.toString())
                when {
                    json.isJsonArray -> json.asJsonArray.map { valueMapper(it) }
                    else -> emptyList()
                }
            }
            else -> throw NoStackTraceException("不支持的类型: ${raw?.javaClass?.name.orEmpty()}")
        }
    } catch (e: Exception) {
        throw NoStackTraceException("parseToList转换失败，${e.message}")
    }
}