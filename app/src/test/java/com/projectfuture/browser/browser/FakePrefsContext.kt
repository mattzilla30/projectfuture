package com.projectfuture.browser.browser

import android.content.ContextWrapper
import android.content.SharedPreferences

/**
 * Minimal in-memory stand-ins for Context/SharedPreferences so HistoryStore,
 * BookmarkStore and Settings (which all take a plain android.content.Context
 * and call getSharedPreferences directly) can be exercised on the host JVM
 * without Robolectric. Each named prefs file gets its own backing map, and
 * repeated getSharedPreferences(name, ...) calls on the same FakePrefsContext
 * return the same instance - mirroring real Android's per-name singleton
 * behavior, which is what lets a second store instance "see" what a first
 * one wrote (simulating the app being killed and reopened).
 */
private class FakeSharedPreferences : SharedPreferences {
    val data = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? =
        if (data.containsKey(key)) data[key] as? String else defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST")
        if (data.containsKey(key)) data[key] as? MutableSet<String> else defValues
    override fun getInt(key: String?, defValue: Int): Int =
        if (data.containsKey(key)) data[key] as? Int ?: defValue else defValue
    override fun getLong(key: String?, defValue: Long): Long =
        if (data.containsKey(key)) data[key] as? Long ?: defValue else defValue
    override fun getFloat(key: String?, defValue: Float): Float =
        if (data.containsKey(key)) data[key] as? Float ?: defValue else defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        if (data.containsKey(key)) data[key] as? Boolean ?: defValue else defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(this)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

private class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
    private val pending = LinkedHashMap<String, Any?>()
    private val removals = mutableSetOf<String>()
    private var clearAll = false

    override fun putString(key: String?, value: String?): SharedPreferences.Editor { pending[key!!] = value; return this }
    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { pending[key!!] = values; return this }
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor { pending[key!!] = value; return this }
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor { pending[key!!] = value; return this }
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { pending[key!!] = value; return this }
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { pending[key!!] = value; return this }
    override fun remove(key: String?): SharedPreferences.Editor { removals.add(key!!); return this }
    override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
    override fun commit(): Boolean { apply(); return true }
    override fun apply() {
        if (clearAll) prefs.data.clear()
        removals.forEach { prefs.data.remove(it) }
        prefs.data.putAll(pending)
    }
}

class FakePrefsContext : ContextWrapper(null) {
    private val stores = mutableMapOf<String, FakeSharedPreferences>()

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        stores.getOrPut(name ?: "default") { FakeSharedPreferences() }
}
