package io.legado.app.utils

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.ParseContext
import com.jayway.jsonpath.ReadContext
import io.legado.app.constant.AppLog
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject

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

private val gson by lazy { GsonBuilder().disableHtmlEscaping().create() }

/**
 * 扩展函数：检查是否为空
 */
private fun Any?.isNullOrEmpty(): Boolean = when (this) {
    null -> true
    is CharSequence -> this.isBlank()
    is Collection<*> -> this.isEmpty()
    is Array<*> -> this.isEmpty()
    is Map<*, *> -> this.isEmpty()
    is NativeArray -> this.length == 0L
    is NativeObject -> this.ids.isEmpty()
    else -> false
}

/**
 * 将 jsValue 转换为 JSON 片段字符串
 */
private fun toJsonFragment(value: Any?): String = when (value) {
    null -> "null"
    is Number -> value.toString()
    is Boolean -> value.toString()
    is String -> gson.toJson(value)
    else -> {
        val context = Context.getCurrentContext()
        if (context != null) {
            gson.toJson(Context.toString(value))
        } else {
            gson.toJson(value)
        }
    }
}

/**
 * NativeObject 转换为 JSON 字符串
 */
fun NativeObject.toJson(): String = buildString {
    append('{')
    var first = true
    for (id in ids) {
        if (!first) append(',')
        first = false
        val key = id.toString()
        val value = get(key, this@toJson)
        append(gson.toJson(key))
        append(':')
        append(
            when (value) {
                is NativeArray -> value.toJson()
                is NativeObject -> value.toJson()
                else -> toJsonFragment(value)
            }
        )
    }
    append('}')
}

/**
 * NativeArray 转换为 JSON 字符串
 */
fun NativeArray.toJson(): String = buildString {
    append('[')
    for (i in 0 until length) {
        if (i > 0) append(',')
        val value = get(i, this@toJson)
        append(
            when (value) {
                is NativeArray -> value.toJson()
                is NativeObject -> value.toJson()
                else -> toJsonFragment(value)
            }
        )
    }
    append(']')
}

/**
 * JsonElement 转换为 JSON 字符串
 */
fun JsonElement.toJson(): String = when {
    isJsonNull -> "null"
    isJsonObject -> gson.toJson(asJsonObject)
    isJsonArray -> gson.toJson(asJsonArray)
    isJsonPrimitive -> {
        val json = asJsonPrimitive
        when {
            json.isBoolean -> json.asBoolean.toString()
            json.isString -> json.asString
            json.isNumber -> json.asBigDecimal.stripTrailingZeros().toPlainString()
            else -> json.toString()
        }
    }
    else -> toString()
}

/**
 * 通用对象转 JSON 字符串
 */
fun toJson(obj: Any?): String {
    return when {
        obj.isNullOrEmpty() -> ""
        obj is Map<*, *> -> gson.toJson(obj)
        obj is List<*> -> gson.toJson(obj)
        obj is Array<*> -> gson.toJson(obj)
        obj is NativeArray -> obj.toJson()
        obj is NativeObject -> obj.toJson()
        obj is JsonElement -> obj.toJson()
        else -> obj.toString()
    }
}

/**
 * 将任意类型转换为 Map<String, String>
 */
fun parseToMap(obj: Any?): Map<String, String> {
    if (obj.isNullOrEmpty()) return emptyMap()

    return try {
        when (obj) {
            is Map<*, *> -> obj.entries.associate {
                it.key.toString() to toJson(it.value)
            }
            is List<*> -> obj.mapIndexed { index, value ->
                index.toString() to toJson(value)
            }.toMap()
            is Array<*> -> obj.mapIndexed { index, value ->
                index.toString() to toJson(value)
            }.toMap()
            is NativeObject -> obj.ids.associate {
                val key = it.toString()
                key to toJson(obj.get(key, obj))
            }
            is NativeArray -> {
                val len = obj.length
                var isEntryList = true
                for (i in 0 until len) {
                    val row = obj.get(i, obj)
                    if (row !is NativeArray || row.length != 2L) {
                        isEntryList = false
                        break
                    }
                }
                if (isEntryList) {
                    for (i in 0 until len) {
                        val row = obj.get(i, obj) as NativeArray
                        val rowKey = row.get(0, row)?.toString() ?: i.toString()
                        val row = toJson(row.get(1, row))
                        rowKey to rowValue
                    }
                } else {
                    for (i in 0 until len) {
                        i.toString() to toJson(obj.get(i, obj))
                    }
                }
            }
            is CharSequence -> {
                val str = obj.toString().trim()
                if (str.isBlank()) return emptyMap()

                val json = JsonParser.parseString(str)
                when {
                    json.isJsonObject -> json.asJsonObject.entrySet().associate {
                        it.key to toJson(it.value)
                    }
                    json.isJsonArray -> json.asJsonArray.mapIndexed { index, value ->
                        index.toString() to toJson(value)
                    }.toMap()
                    else -> emptyMap()
                }
            }
            else -> {
                AppLog.put("toMapWithString: 不支持的类型 ${obj!!::class.java.simpleName}")
                emptyMap()
            }
        }
    } catch (e: Exception) {
        AppLog.put("toMapWithString 转换失败", e)
        emptyMap()
    }
}

/**
 * 将任意值展平为 Map 可接受的类型
 */
private fun flattenValue(value: Any?): Any? = when {
    value == null -> null
    value is Boolean || value is Number -> value
    value is String -> value.toString()
    value is Map<*, *> -> value.entries.associate {
        it.key.toString() to flattenValue(it.value)
    }
    value is List<*> -> value.mapIndexed { i, v ->
        i.toString() to flattenValue(v)
    }.toMap()
    value is Array<*> -> value.mapIndexed { i, v ->
        i.toString() to flattenValue(v)
    }.toMap()
    value is NativeObject -> value.ids.associate {
        val key = it.toString()
        key to flattenValue(value.get(key, value))
    }
    value is NativeArray -> {
        val len = value.length
        var isEntryList = true
        for (i in 0 until len) {
            val row = value.get(i, value)
            if (row !is NativeArray || row.length != 2L) {
                isEntryList = false
                break
            }
        }
        if (isEntryList) {
            for (i in 0 until len) {
                val row = value.get(i, value) as NativeArray
                val rowKey = row.get(0, row)?.toString() ?: i.toString()
                val rowValue = flattenValue(row.get(1, row))
                rowKey to rowValue
            }
        } else {
            for (i in 0 until len) {
                i.toString() to flattenValue(value.get(i, value))
            }
        }
    }
    value is JsonElement -> when {
        value.isJsonNull -> null
        value.isJsonObject -> value.asJsonObject.entrySet().associate {
            it.key to flattenValue(it.value)
        }
        value.isJsonArray -> value.asJsonArray.mapIndexed { i, e ->
            i.toString() to flattenValue(e)
        }.toMap()
        value.isJsonPrimitive -> with(value.asJsonPrimitive) {
            when {
                isBoolean -> asBoolean
                isNumber -> asNumber.let {
                    val num = it.toDouble()
                    if (num % 1 == 0.0) it.toLong() else num
                }
                isString -> asString
                else -> toString()
            }
        }
        else -> value.toString()
    }
    else -> value.toString()
}

/**
 * 将任意类型转换为 Map<String, Any?>
 */
fun parseToMapWithAny(obj: Any?): Map<String, Any?> {
    if (obj.isNullOrEmpty()) return emptyMap()

    return try {
        when (obj) {
            is Map<*, *> -> obj.entries.associate {
                it.key.toString() to flattenValue(it.value)
            }
            is List<*> -> obj.mapIndexed { index, value ->
                index.toString() to flattenValue(value)
            }.toMap()
            is Array<*> -> obj.mapIndexed { index, value ->
                index.toString() to flattenValue(value)
            }.toMap()
            is NativeObject -> obj.ids.associate {
                val key = it.toString()
                key to flattenValue(obj.get(key, obj))
            }
            is NativeArray -> {
                val len = obj.length
                var isEntryList = true
                for (i in 0 until len) {
                    val row = obj.get(i, obj)
                    if (row !is NativeArray || row.length != 2L) {
                        isEntryList = false
                        break
                    }
                }
                if (isEntryList) {
                    for (i in 0 until len) {
                        val row = obj.get(i, obj) as NativeArray
                        val rowKey = row.get(0, row)?.toString() ?: i.toString()
                        val rowValue = flattenValue(row.get(1, row))
                        rowKey to rowValue
                    }
                } else {
                    for (i in 0 until len) {
                        i.toString() to flattenValue(obj.get(i, obj))
                    }
                }
            }
            is CharSequence -> {
                val str = obj.toString().trim()
                if (str.isBlank()) return emptyMap()

                val json = JsonParser.parseString(str)
                when {
                    json.isJsonObject -> json.asJsonObject.entrySet().associate {
                        it.key to flattenValue(it.value)
                    }
                    json.isJsonArray -> json.asJsonArray.mapIndexed { index, value ->
                        index.toString() to flattenValue(value)
                    }.toMap()
                    else -> emptyMap()
                }
            }
            else -> {
                AppLog.put("toMapWithAny: 不支持的类型 ${obj!!::class.java.simpleName}")
                emptyMap()
            }
        }
    } catch (e: Exception) {
        AppLog.put("toMapWithAny 转换失败", e)
        emptyMap()
    }
}