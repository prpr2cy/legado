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
import io.legado.app.constant.AppLog
import java.lang.reflect.Type
import java.math.BigDecimal
import org.mozilla.javascript.Undefined

val jsonPath: ParseContext by lazy {
    JsonPath.using(
        Configuration.builder()
            .options(Option.SUPPRESS_EXCEPTIONS)
            .build()
    )
}

fun ReadContext.readString(path: String): String? = read(path, String::class.java)
fun ReadContext.readBool(path: String): Boolean? = read(path, Boolean::class.java)
fun ReadContext.readInt(path: String): Int? = read(path, Int::class.java)
fun ReadContext.readLong(path: String): Long? = read(path, Long::class.java)

private val gson by lazy {
    GsonBuilder().disableHtmlEscaping()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .serializeNulls()
        .create()
}

private val undefined = Undefined.instance

private fun Any?.isNullOrEmpty(): Boolean = when (this) {
    null, undefined -> true
    is String -> isBlank()
    is CharSequence -> isBlank()
    is Map<*, *> -> isEmpty()
    is List<*> -> isEmpty()
    is Array<*> -> isEmpty()
    else -> false
}

fun toJsonString(raw: Any?): String = when (raw) {
    null, undefined -> ""
    is Boolean -> raw.toString()
    is Number -> {
        is Long, is Int, is Short, is Byte -> raw.toString()
        is Double -> {
            if (raw % 1.0 == 0.0) raw.toLong().toString()
            else BigDecimal.valueOf(raw).stripTrailingZeros().toPlainString()
        }
        else -> BigDecimal.valueOf(raw.toDouble()).stripTrailingZeros().toPlainString()
    }
    is String -> raw
    is CharSequence -> raw.toString()
    is Map<*, *> -> gson.toJson(toAnyValue(raw))
    is List<*> -> gson.toJson(toAnyValue(raw))
    is Array<*> -> gson.toJson(toAnyValue(raw))
    is JsonElement -> gson.toJson(raw)
    else -> raw.toString()
}

private fun toAnyValue(raw: Any?): Any? = when (raw) {
    null, undefined -> null
    is Boolean -> raw
    is Number -> if (raw is Double && raw % 1.0 == 0.0) raw.toLong() else raw
    is String -> raw
    is CharSequence -> raw.toString()
    is Map<*, *> -> raw.entries.associate { it.key.toString() to toAnyValue(it.value) }
    is List<*> -> raw.map { toAnyValue(it) }
    is Array<*> -> raw.map { toAnyValue(it) }
    is JsonElement -> when {
        raw.isJsonNull -> null
        raw.isJsonObject -> raw.asJsonObject.entrySet().associate {
            it.key to toAnyValue(it.value)
        }
        raw.isJsonArray -> raw.asJsonArray.mapIndexed { index, value ->
            index.toString() to toAnyValue(value)
        }
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

private inline fun <T> collectionToMap(
    list: List<*>,
    valueMapper: (Any?) -> T
): Map<String, T> {
    // 如果不是 [[k,v],[k,v]] 形式，就按索引构造
    val isKvPairs = !list.any { it !is List<*> || it.size != 2 }
    return if (isKvPairs) {
        list.associate { item ->
            val (k, v) = item as List<*>
            k.toString() to valueMapper(v)
        }
    } else {
        list.mapIndexed { i, v -> i.toString() to valueMapper(v) }
            .associate { it.first to it.second }
    }
}

private inline fun <T> collectionToMap(
    array: Array<*>,
    valueMapper: (Any?) -> T
): Map<String, T> = collectionToMap(array.asList(), valueMapper)

private inline fun <T> parseToMapImpl(
    raw: Any?,
    valueMapper: (Any?) -> T
): Map<String, T> {
    if (raw.isNullOrEmpty()) return emptyMap()

    return try {
        when (raw) {
            is Map<*, *> -> raw.entries.associate {
                it.key.toString() to valueMapper(it.value)
            }
            is List<*> -> collectionToMap(raw, valueMapper)
            is Array<*> -> collectionToMap(raw, valueMapper)
            is CharSequence -> {
                val json = JsonParser.parseString(raw.toString())
                when {
                    json.isJsonObject -> json.asJsonObject.entrySet()
                        .associate { it.key to valueMapper(it.value) }
                    json.isJsonArray -> json.asJsonArray
                        .mapIndexed { i, v -> i.toString() to valueMapper(v) }
                        .associate { it.first to it.second }
                    else -> emptyMap()
                }
            }
            else -> {
                AppLog.put("parseToMap: 不支持的类型 ${raw?.javaClass?.name.orEmpty()}")
                emptyMap()
            }
        }
    } catch (e: Exception) {
        AppLog.put("parseToMap: 转换失败 ${raw?.javaClass?.name.orEmpty()}", e)
        emptyMap()
    }
}

fun parseToMap(raw: Any?): Map<String, String> =
    parseToMapImpl(raw) { toJsonString(it) }

fun parseToMapWithAny(raw: Any?): Map<String, Any?> =
    parseToMapImpl(raw) { toAnyValue(it) }