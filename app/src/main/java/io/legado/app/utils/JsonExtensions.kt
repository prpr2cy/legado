package io.legado.app.utils

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.ToNumberPolicy
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.ParseContext
import com.jayway.jsonpath.ReadContext
import io.legado.app.constant.AppLog
import java.lang.reflect.Type
import java.math.BigDecimal

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
        .registerTypeAdapter(Number::class.java, object: JsonSerializer<Number> {
            override fun serialize(src: Number, type: Type, context: JsonSerializationContext): JsonElement {
                val num = if (src is Double && src % 1.0 == 0.0) src.toLong() else src 
                return JsonPrimitive(num)
            }
        })
        .registerTypeAdapter(Double::class.java, object: JsonSerializer<Double> {
            override fun serialize(src: Double, type: Type, context: JsonSerializationContext): JsonElement {
                val num = if (src % 1.0 == 0.0) src.toLong() else src 
                return JsonPrimitive(num)
            }
        })
        .serializeNulls()
        .create()
}

private fun Any?.isNullOrEmpty(): Boolean = when (this) {
    null -> true
    is String -> isBlank()
    is CharSequence -> isBlank()
    is Map<*, *> -> isEmpty()
    is List<*> -> isEmpty()
    is Array<*> -> isEmpty()
    else -> false
}

private fun Number.toJsonString(): String = when (this) {
    is Long, is Int, is Short, is Byte -> toString()
    is Double -> {
        if (this % 1.0 == 0.0) toLong().toString()
        else BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()
    }
    else -> BigDecimal.valueOf(toDouble()).stripTrailingZeros().toPlainString()
}

private fun JsonElement.toJson(): String = when {
    isJsonNull -> "null"
    isJsonObject -> asJsonObject.entrySet()
        .joinToString(",", "{", "}") { "\"${it.key}\":${it.value.toJson()}" }
    isJsonArray -> asJsonArray
        .joinToString(",", "[", "]") { it.toJson() }
    isJsonPrimitive -> asJsonPrimitive.let {
        when {
            it.isBoolean -> it.asBoolean.toString()
            it.isNumber -> it.asBigDecimal.stripTrailingZeros().toPlainString()
            it.isString -> gson.toJson(it.asString)
            else -> it.toString()
        }
    }
    else -> toString()
}

private fun toJsonRaw(raw: Any?): Any? = when (raw) {
    null -> null
    is Number -> {
        if (raw is Double && raw % 1.0 == 0.0) raw.toLong() else raw
    }
    is CharSequence -> raw.toString()
    is Map<*, *> -> raw.map { (k, v) -> k.toString() to toJsonRaw(v) }.toMap()
    is List<*> -> raw.map { toJsonRaw(it) }
    is Array<*> -> raw.map { toJsonRaw(it) }
    else -> raw
}

fun toJsonString(raw: Any?): String = when (raw) {
    null -> "null"
    is Boolean -> raw.toString()
    is Number -> raw.toString()
    is String -> gson.toJson(raw)
    is CharSequence -> gson.toJson(raw.toString())
    is Map<*, *> -> gson.toJson(raw)
    is List<*> -> gson.toJson(raw)
    is Array<*> -> gson.toJson(raw)
    is JsonElement -> gson.toJson(raw)
    else -> raw.toString()
}

private fun toAnyValue(raw: Any?): Any? {
AppLog.put(if (raw != null) raw::class.java else "null")
return when (raw) {
    null -> null
    is Boolean -> raw
    is Number -> raw
    //if (raw is Double && raw % 1.0 == 0.0) raw.toLong() else raw
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
                isNumber -> asNumber
                /*.let {
                    if (it is Double && it % 1.0 == 0.0) it.toLong() else it
                }
                */
                isString -> asString
                else -> raw
            }
        }
        else -> raw
    }
    else -> raw
}
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
                AppLog.put("parseToMap: 不支持的类型 ${raw?.javaClass?.simpleName.orEmpty()}")
                emptyMap()
            }
        }
    } catch (e: Exception) {
        AppLog.put("parseToMap: 转换失败 ${raw?.javaClass?.simpleName.orEmpty()}", e)
        emptyMap()
    }
}

fun parseToMap(raw: Any?): Map<String, String> =
    parseToMapImpl(raw) { toJsonString(it) }

fun parseToMapWithAny(raw: Any?): Map<String, Any?> =
    parseToMapImpl(raw) { toAnyValue(it) }