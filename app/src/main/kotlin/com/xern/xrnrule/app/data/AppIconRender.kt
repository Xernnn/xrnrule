package com.xern.xrnrule.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.toBitmap
import com.xern.xrnrule.domain.Icons
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * This phone's logos as [IconUpload] sends them: an app's own icon as the launcher draws it, a
 * site's as [SiteIconCache] keeps it; each a PNG of at most [SIDE] px under [Icons.MAX_BYTES].
 */
class AppIconRender(private val context: Context, private val siteIcons: SiteIconCache) : IconUpload.Render {

    override fun app(packageName: String): ByteArray? = runCatching {
        val icon = context.packageManager.getApplicationIcon(packageName)
        // An icon full of detail can come out too big at 96 px: smaller, then.
        listOf(SIDE, 72, 48).firstNotNullOfOrNull { side -> png(icon.toBitmap(side, side, Bitmap.Config.ARGB_8888)) }
    }.getOrNull()

    override fun site(host: String): ByteArray? = runCatching {
        val bytes = siteIcons.kept(host) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val side = maxOf(bounds.outWidth, bounds.outHeight)
        if (side <= 0 || side > 4096) return null
        var sample = 1
        while (side / (sample * 2) >= SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val scale = minOf(1f, SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val fitted = if (scale >= 1f) bitmap else Bitmap.createScaledBitmap(
            bitmap, maxOf(1, (bitmap.width * scale).roundToInt()), maxOf(1, (bitmap.height * scale).roundToInt()), true,
        )
        png(fitted)
    }.getOrNull()

    private fun png(bitmap: Bitmap): ByteArray? =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().takeIf { it.size <= Icons.MAX_BYTES }

    private companion object {
        const val SIDE = 96
    }
}
