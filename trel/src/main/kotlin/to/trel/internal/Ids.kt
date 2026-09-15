package to.trel.internal

import java.security.SecureRandom
import java.util.concurrent.ThreadLocalRandom

internal object Ids {
    private val secure = SecureRandom()
    private const val HEX = "0123456789abcdef"

    private fun hex(bytes: Int): String {
        val rnd = ThreadLocalRandom.current()
        val sb = StringBuilder(bytes * 2)
        repeat(bytes) {
            val b = rnd.nextInt(256)
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0f])
        }
        return sb.toString()
    }

    fun traceId(): String = hex(16)
    fun spanId(): String = hex(8)

    /** Sortable file name: millis + random suffix (ULID-like, sufficient for queue ordering). */
    fun ulid(): String {
        val ms = System.currentTimeMillis().toString(16).padStart(12, '0')
        val bytes = ByteArray(8).also { secure.nextBytes(it) }
        return ms + bytes.joinToString("") { "%02x".format(it) }
    }
}
