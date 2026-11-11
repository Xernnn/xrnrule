package com.xern.xrnrule.app.data

import android.content.SharedPreferences

/** [SharedPreferences] in memory, for the classes that keep a few values there. */
class MemoryPrefs(initial: Map<String, Any?> = emptyMap()) : SharedPreferences {
    val values = HashMap(initial)

    override fun getAll(): Map<String, *> = HashMap(values)
    override fun getString(key: String, defValue: String?): String? = values[key] as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as Set<String>? ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val put = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = also { put[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = also { put[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = also { put[key] = value }
        override fun putLong(key: String, value: Long) = also { put[key] = value }
        override fun putFloat(key: String, value: Float) = also { put[key] = value }
        override fun putBoolean(key: String, value: Boolean) = also { put[key] = value }
        override fun remove(key: String) = also { removed += key }
        override fun clear() = also { clear = true }

        override fun commit(): Boolean {
            if (clear) values.clear()
            removed.forEach(values::remove)
            put.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
