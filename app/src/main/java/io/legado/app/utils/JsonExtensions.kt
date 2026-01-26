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
 * 将 jsValue 转换为 JSON 片段字符串
 */
private fun toJsonFragment(value: Any?): String = when (value) {
    null -> "null"
    is String -> gson.toJson(value)
    is Number -> value.toString()
    is Boolean -> value.toString()
    else -> gson.toJson(Context.toString(Context.getCurrentContext(), value))
}

/**
 * NativeArray 转换为 JSON 字符串
 */
fun NativeArray.valueOf(): String = buildString {
    append('[')
    for (i in 0 until length) {
        if (i > 0) append(',')
        val value = get(i, this@valueOf)
        append(
            when (value) {
                is NativeArray -> value.valueOf()
                is NativeObject -> value.valueOf()
                else -> toJsonFragment(value)
            }
        )
    }
    append(']')
}

/**
 * NativeObject 转换为 JSON 字符串
 */
fun NativeObject.valueOf(): String = buildString {
    append('{')
    var first = true
    for (id in ids) {
        if (!first) append(',')
        first = false
        val key = id.toString()
        val value = get(key, this@valueOf)
        append(gson.toJson(key))
        append(':')
        append(
            when (value) {
                is NativeArray -> value.valueOf()
                is NativeObject -> value.valueOf()
                else -> toJsonFragment(value)
            }
        )
    }
    append('}')
}

/**
 * JsonElement 转换为字符串
 */
fun JsonElement.valueOf(): String = when {
    isJsonNull -> "null"
    isJsonArray -> gson.toJson(asJsonArray)
    isJsonObject -> gson.toJson(asJsonObject)
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
        obj is NativeArray -> obj.valueOf()
        obj is NativeObject -> obj.valueOf()
        obj is JsonElement -> obj.valueOf()
        else -> obj.toString()
    }
}

/**
 * 将任意类型转换为 Map<String, String>
 */
fun convertToMap(obj: Any?): Map<String, String> {
    if (obj.isNullOrEmpty()) return emptyMap()

    return try {
        when (obj) {
            // 1. Java Map
            is Map<*, *> -> {
                obj.entries.associate { entry ->
                    val key = entry.key?.toString() ?: ""
                    val value = toJson(entry.value)
                    key to value
                }
            }

            // 2. JavaScript 数组
            is NativeArray -> {
                val len = obj.length

                // 检查是否是 [[key, value], ...] 格式
                var isEntryList = true
                for (i in 0 until len) {
                    val row = obj.get(i, obj)
                    if (row !is NativeArray || row.length != 2) {
                        isEntryList = false
                        break
                    }
                }

                if (isEntryList) {
                    // 处理 [[key, value], ...] 格式
                    (0 until len).associate { i ->
                        val row = obj.get(i, obj) as NativeArray
                        val key = row.get(0, row)?.toString() ?: i.toString()
                        val value = toJson(row.get(1, row))
                        key to value
                    }
                } else {
                    // 处理普通数组
                    (0 until len).associate { i ->
                        i.toString() to toJson(obj.get(i, obj))
                    }
                }
            }

            // 3. JavaScript 对象
            is NativeObject -> {
                obj.ids.associate { id ->
                    val key = id.toString()
                    val value = toJson(obj.get(key, obj))
                    key to value
                }
            }

            // 4. JSON 字符串
            is CharSequence -> {
                val jsonStr = obj.toString().trim()
                if (jsonStr.isBlank()) return emptyMap()

                val json = JsonParser.parseString(jsonStr)
                when {
                    json.isJsonArray -> {
                        json.asJsonArray.mapIndexed { index, value ->
                            index.toString() to toJson(value)
                        }.toMap()
                    }
                    json.isJsonObject -> {
                        json.asJsonObject.entrySet().associate { (key, value) ->
                            key to toJson(value)
                        }
                    }
                    else -> emptyMap()
                }
            }

            // 5. 其他不支持的类型
            else -> {
                AppLog.put("convertToMap: 不支持的类型 ${obj::class.java.simpleName}")
                emptyMap()
            }
        }
    } catch (e: Exception) {
        AppLog.put("convertToMap 转换失败: ${obj::class.java.simpleName}", e)
        emptyMap()
    }
}

