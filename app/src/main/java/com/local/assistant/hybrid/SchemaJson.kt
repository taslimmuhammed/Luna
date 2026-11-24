@file:OptIn(com.google.firebase.ai.type.PublicPreviewAPI::class)

package com.local.assistant.hybrid

import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.Schema
import com.google.gson.JsonObject as GsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Between the app's JSON and Firebase AI Logic's types: the OpenAPI tool declarations the app
 * already gives the on-device model ([com.local.assistant.memory.tools.ToolCatalog]) as
 * [FunctionDeclaration]s, and a call's arguments and a tool's result both ways.
 */
object SchemaJson {

    /** One declaration, `{"name", "description", "parameters": {"type": "object", ...}}`. */
    fun declaration(json: String): FunctionDeclaration {
        val root = JsonParser.parseString(json).asJsonObject
        val parameters = root.getAsJsonObject("parameters")
        val properties = parameters?.getAsJsonObject("properties")
        val required = parameters?.getAsJsonArray("required")?.map { it.asString }.orEmpty().toSet()
        val schemas = properties?.entrySet()?.associate { (key, value) -> key to schema(value.asJsonObject) }.orEmpty()
        return FunctionDeclaration(
            root.get("name").asString,
            root.get("description")?.asString.orEmpty(),
            schemas,
            schemas.keys.filter { it !in required },
        )
    }

    /** A JSON Schema as Firebase's [Schema]; for a reply constrained to JSON, too. */
    fun schema(json: String): Schema = schema(JsonParser.parseString(json).asJsonObject)

    private fun schema(node: GsonObject): Schema {
        val description = node.get("description")?.takeIf { it.isJsonPrimitive }?.asString
        // ["string", "null"]: nullable.
        val types = node.get("type")?.let { type ->
            if (type.isJsonArray) type.asJsonArray.map { it.asString } else listOf(type.asString)
        }.orEmpty()
        val nullable = "null" in types
        val type = types.firstOrNull { it != "null" } ?: if (node.has("properties")) "object" else "string"
        node.getAsJsonArray("enum")?.let { values ->
            return Schema.enumeration(values.map { it.asString }, description, nullable)
        }
        return when (type) {
            "integer" -> Schema.integer(description, nullable)
            "number" -> Schema.double(description, nullable)
            "boolean" -> Schema.boolean(description, nullable)
            "array" -> Schema.array(
                node.getAsJsonObject("items")?.let(::schema) ?: Schema.string(),
                description,
                nullable,
            )
            "object" -> {
                val properties = node.getAsJsonObject("properties")?.entrySet()
                    ?.associate { (key, value) -> key to schema(value.asJsonObject) }.orEmpty()
                val required = node.getAsJsonArray("required")?.map { it.asString }.orEmpty().toSet()
                Schema.obj(properties, properties.keys.filter { it !in required }, description, nullable)
            }
            else -> Schema.string(description, nullable)
        }
    }

    /** A call's arguments as the app's tools read them ([com.local.assistant.memory.tools.Args]). */
    fun arguments(args: Map<String, JsonElement>): Map<String, Any?> = args.mapValues { (_, value) -> value(value) }

    private fun value(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.doubleOrNull ?: element.content
        }
        is JsonArray -> element.map(::value)
        is JsonObject -> element.mapValues { (_, it) -> value(it) }
    }

    /** The app's values as JSON, for a stored call replayed to the cloud model. */
    fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        // Whole numbers whole: Gson reads 3 as 3.0.
        is Double -> if (value % 1.0 == 0.0 && value in -9.0e15..9.0e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
        is Float -> element(value.toDouble())
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to element(v) })
        is Iterable<*> -> JsonArray(value.map(::element))
        is Array<*> -> JsonArray(value.map(::element))
        else -> JsonPrimitive(value.toString())
    }

    /** A tool's result, JSON text, as the object a function response carries. */
    fun result(json: String): JsonObject {
        val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(json) }.getOrNull()
        return parsed as? JsonObject ?: JsonObject(mapOf("result" to (parsed ?: JsonPrimitive(json))))
    }
}
