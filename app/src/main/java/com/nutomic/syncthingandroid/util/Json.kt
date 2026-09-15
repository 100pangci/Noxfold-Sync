package com.nutomic.syncthingandroid.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Shared kotlinx.serialization configuration for Syncthing REST API payloads and
 * persisted JSON state.
 *
 * Mirrors the previous Gson behaviour:
 *  - unknown fields are ignored (Gson ignored them),
 *  - null fields are omitted when encoding (Gson's default),
 *  - default values are always written (Gson serialised every field),
 *  - missing fields fall back to the Kotlin default value, while explicit nulls
 *    decode to null for nullable fields (Gson behaviour).
 */
@OptIn(ExperimentalSerializationApi::class)
val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@OptIn(ExperimentalSerializationApi::class)
val prettyJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    prettyPrint = true
}

/**
 * Serialisation-based deep copy, replacing the former Gson `Util.deepCopy`.
 * Use only for models that do not expose an explicit `copy()`.
 */
inline fun <reified T> deepCopy(value: T): T =
    json.decodeFromString(json.encodeToString(value))
