package to.trel.internal

import android.os.Handler
import android.os.Looper
import android.view.Choreographer

/** Counts frames slower than ~32ms (slow) and ~700ms (frozen) via Choreographer. */
internal class Frames {
    private val main = Handler(Looper.getMainLooper())
    private var last = 0L
    private var slow = 0
    private var frozen = 0
    private var running = false

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (last != 0L) {
                val dtMs = (frameTimeNanos - last) / 1_000_000
                if (dtMs > 700) frozen++
                else if (dtMs > 32) slow++
            }
            last = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        running = true
        main.post { Choreographer.getInstance().postFrameCallback(callback) }
    }

    fun consume(): Pair<Int, Int> {
        val out = slow to frozen
        slow = 0
        frozen = 0
        return out
    }
}
