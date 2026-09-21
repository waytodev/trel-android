package to.trel.internal

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** JPEG of the resumed activity's window, optionally with text painted over. */
internal object Screenshot {
    fun capture(lifecycle: Lifecycle, maskText: Boolean): ByteArray? {
        val activity = lifecycle.currentActivity ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) return draw(activity, maskText)
        val box = arrayOfNulls<ByteArray>(1)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            box[0] = draw(activity, maskText)
            latch.countDown()
        }
        latch.await(400, TimeUnit.MILLISECONDS)
        return box[0]
    }

    private fun draw(activity: Activity, maskText: Boolean): ByteArray? = runCatching {
        val view = activity.window?.decorView ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val scale = minOf(1f, 480f / view.width)
        val w = (view.width * scale).toInt().coerceAtLeast(1)
        val h = (view.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.scale(scale, scale)
        view.draw(canvas)
        canvas.restore()
        if (maskText) mask(view, canvas, scale)
        val out = ByteArrayOutputStream()
        var quality = 40
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        while (out.size() > 180 * 1024 && quality > 10) {
            out.reset()
            quality -= 10
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        bitmap.recycle()
        out.toByteArray()
    }.getOrNull()

    private fun mask(view: View, canvas: Canvas, scale: Float) {
        val paint = Paint().apply { color = Color.BLACK }
        fun walk(v: View) {
            if (v is TextView && v.width > 0 && v.height > 0) {
                val loc = IntArray(2)
                v.getLocationInWindow(loc)
                val root = IntArray(2)
                (v.rootView).getLocationInWindow(root)
                val rect = Rect(
                    ((loc[0] - root[0]) * scale).toInt(),
                    ((loc[1] - root[1]) * scale).toInt(),
                    ((loc[0] - root[0] + v.width) * scale).toInt(),
                    ((loc[1] - root[1] + v.height) * scale).toInt(),
                )
                canvas.drawRect(rect, paint)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(view)
    }
}
