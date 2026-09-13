package com.nutomic.syncthingandroid.service

import android.content.SharedPreferences

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

import java.io.IOException

/**
 * Persistence boundary for the last verified forwarded snapshot.
 *
 * The first implementation remains SharedPreferences for migration safety. The bridge no longer
 * depends on Gson or preference key layout, so a transactional row-based implementation can be
 * introduced independently later.
 */
internal interface SnapshotStore {
    fun loadBridge(bridgeId: String): Map<String, SafBridge.NodeInfo>

    fun loadSubtree(bridgeId: String, path: String): Map<String, SafBridge.NodeInfo> {
        val snapshot = loadBridge(bridgeId)
        if (path.isEmpty()) {
            return snapshot
        }
        return snapshot.filterKeys { it == path || it.startsWith("$path/") }
    }

    fun putEntries(bridgeId: String, entries: Map<String, SafBridge.NodeInfo>): Boolean

    fun removeEntries(bridgeId: String, paths: Set<String>): Boolean {
        if (paths.isEmpty()) {
            return true
        }
        val snapshot = loadBridge(bridgeId).toMutableMap()
        paths.forEach { snapshot.remove(it) }
        return replaceFullSnapshot(bridgeId, snapshot)
    }

    fun replaceFullSnapshot(bridgeId: String, snapshot: Map<String, SafBridge.NodeInfo>): Boolean

    fun clearBridge(bridgeId: String): Boolean

    /** Loads, transforms, and commits one bridge snapshot as one store operation. */
    fun transaction(
        bridgeId: String,
        transform: (MutableMap<String, SafBridge.NodeInfo>) -> Unit,
    ): Boolean {
        val snapshot = loadBridge(bridgeId).toMutableMap()
        transform(snapshot)
        return replaceFullSnapshot(bridgeId, snapshot)
    }
}

/** SharedPreferences adapter retained for compatibility with existing persisted state. */
internal class SharedPreferencesSnapshotStore(
    private val prefs: SharedPreferences,
    private val gson: Gson,
) : SnapshotStore {

    companion object {
        const val STATE_PREFIX = "saf_bridge_state_"
    }

    private val stateType = object : TypeToken<Map<String, SafBridge.NodeInfo>>() {}.type

    override fun loadBridge(bridgeId: String): Map<String, SafBridge.NodeInfo> {
        val json = prefs.getString(STATE_PREFIX + bridgeId, null) ?: return emptyMap()
        return try {
            val parsed: Map<String, SafBridge.NodeInfo>? = gson.fromJson(json, stateType)
            parsed ?: throw IOException("Snapshot is null")
        } catch (e: Exception) {
            throw IOException("Corrupt state pref for $bridgeId", e)
        }
    }

    override fun putEntries(
        bridgeId: String,
        entries: Map<String, SafBridge.NodeInfo>,
    ): Boolean {
        return transaction(bridgeId) { it.putAll(entries) }
    }

    override fun replaceFullSnapshot(
        bridgeId: String,
        snapshot: Map<String, SafBridge.NodeInfo>,
    ): Boolean {
        return prefs.edit()
            .putString(STATE_PREFIX + bridgeId, gson.toJson(snapshot))
            .commit()
    }

    override fun clearBridge(bridgeId: String): Boolean {
        return prefs.edit().remove(STATE_PREFIX + bridgeId).commit()
    }
}
