package com.xern.xrnrule.app.data

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/**
 * The [max] most lately used values, safe from any thread. A value [getOrLoad] can't load isn't
 * kept, so it's tried again next time. Pure, so it's tested without a phone.
 */
class Lru<K : Any, V : Any>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
    }

    @Synchronized
    operator fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map[key] = value
    }

    @Synchronized
    fun clear() = map.clear()

    /** The kept value, or [load]'s, kept for next time. [load] runs outside the lock: it may be slow. */
    fun getOrLoad(key: K, load: (K) -> V?): V? = get(key) ?: load(key)?.also { put(key, it) }

    val size: Int @Synchronized get() = map.size
}

/**
 * App names, looked up once: each is a binder call into PackageManager, and the block screen and
 * the service asked for them on every redraw, once a second on the main thread (N5).
 */
object AppLabels {
    private val labels = Lru<String, String>(256)

    fun of(context: Context, pkg: String): String = labels.getOrLoad(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    } ?: pkg
}

/**
 * App icons, drawn once at [SIDE] px and kept for the most lately shown: each row of the app
 * picker and each card decoded its icon on the main thread as it came into view (N6). [load] is
 * for a background thread; [cached] is what a screen can show at once.
 */
object AppIcons {
    const val SIDE = 144
    private val icons = Lru<String, ImageBitmap>(80)

    fun cached(pkg: String): ImageBitmap? = icons[pkg]

    fun load(context: Context, pkg: String): ImageBitmap? = icons.getOrLoad(pkg) {
        runCatching { context.packageManager.getApplicationIcon(it).toBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888).asImageBitmap() }.getOrNull()
    }
}
