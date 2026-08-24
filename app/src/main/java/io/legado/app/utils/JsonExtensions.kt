package io.legado.app.utils

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.ToNumberPolicy
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.ParseContext
import com.jayway.jsonpath.ReadContext
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
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

private fun Any?.isNullOrEmpty(): Boolean = when (this) {
    null -> true
    is String -> isBlank()
    is CharSequence -> isBlank()
    is Map<*, *> -> isEmpty()
    is List<*> -> isEmpty()
    is Array<*> -> isEmpty()
    else -> false
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
    is Map<*, *> -> Gson.toJson(toAnyValue(raw))
    is List<*> -> Gson.toJson(toAnyValue(raw))
    is Array<*> -> Gson.toJson(toAnyValue(raw))
    is JsonElement -> Gson.toJson(raw)
    else -> try {
        Gson.toJson(raw)
    } catch (e: Exception) {
        raw.toString()
    }
}

/**
 * 递归转换任意对象为 Kotlin 友好的纯数据类型。
 * - 容器类型递归处理内部元素
 * - Double 的整数值会被优化为 Long
 */
fun toAnyValue(raw: Any?): Any? = when (raw) {
    null -> null
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
        raw.isJsonArray -> raw.asJsonArray.map { toAnyValue(it) }
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

/**
 * 将外部输入（JS 侧或 JSON）统一包装为 Kotlin 的 Map / List / null。
 * - Map / Object → Map<String, Any?>
 * - List / Array → List<Any?>
 * - JSON 字符串 → 解析为 Map 或 List（纯 primitive 返回 null）
 * - 其他无法转换的 → null
 */
fun toJsonWrapper(raw: Any?): Any? {
    return when (raw) {
        null -> null
        is Map<*, *> -> toAnyValue(raw)
        is List<*> -> toAnyValue(raw)
        is Array<*> -> toAnyValue(raw)
        is CharSequence -> {
            try {
                val trimmed = raw.toString().trim()
                if (trimmed.isEmpty()) return null
                val json = JsonParser.parseString(trimmed)
                when {
                    json.isJsonObject -> toAnyValue(json)
                    json.isJsonArray -> toAnyValue(json)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
        else -> null
    }
}

/**
 * 将 Kotlin/Java 容器递归转换为 Rhino JS 的 NativeArray / NativeObject，
 * 方便直接作为返回值传给 JS 引擎。
 * - List / Array → NativeArray
 * - Map → NativeObject
 * - Gson 的 JsonArray / JsonObject → NativeArray / NativeObject
 * - Gson 的 JsonPrimitive → Boolean / Number / String
 * - null → null
 * - 其他（String、Number、Boolean 等）→ 原样返回
 */
fun wrapperToJS(raw: Any?): Any? = when (raw) {
    null -> null
    is NativeArray -> raw
    is NativeObject -> raw
    is Map<*, *> -> NativeObject().apply {
        raw.forEach { (k, v) ->
            put(k.toString(), this, wrapToJS(v))
        }
    }
    is List<*> -> NativeArray(raw.map { wrapToJS(it) }.toTypedArray())
    is Array<*> -> NativeArray(raw.map { wrapToJS(it) }.toTypedArray())
    is JsonElement -> when {
        raw.isJsonNull -> null
        raw.isJsonObject -> NativeObject().apply {
            raw.asJsonObject.entrySet().forEach { (k, v) ->
                put(k, this, wrapToJS(v))
            }
        }
        raw.isJsonArray -> NativeArray(raw.asJsonArray.map { wrapToJS(it) }.toTypedArray())
        raw.isJsonPrimitive -> raw.asJsonPrimitive.let {
            when {
                it.isBoolean -> it.asBoolean
                it.isNumber -> it.asNumber.let { n ->
                    if (n is Double && n % 1.0 == 0.0) n.toLong() else n
                }
                it.isString -> it.asString
                else -> raw
            }
        }
        else -> raw
    }
    else -> raw
}