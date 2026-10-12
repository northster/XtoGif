package io.github.northster.xsaver.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Tolerant readers: the APIs are unofficial, so a missing or differently typed field must not break the whole parse.

internal fun parseObject(body: String): JsonObject? = try {
    Json.parseToJsonElement(body) as? JsonObject
} catch (_: Exception) {
    null
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

internal fun JsonObject.long(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.content.toLongOrNull() }

internal fun JsonObject.double(key: String): Double? = prim(key)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }

private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
