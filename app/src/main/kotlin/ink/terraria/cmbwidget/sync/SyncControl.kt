package ink.terraria.cmbwidget.sync

import java.io.Closeable
import java.util.concurrent.CancellationException

/** Closing the transport also interrupts an IMAP connect, TLS handshake or socket read. */
internal class SyncControl(private val stopped: () -> Boolean = { false }) {
    private val monitor = Any()
    @Volatile private var cancelled = false
    private var connection: Closeable? = null

    val isCancelled: Boolean get() = cancelled || stopped()

    fun check() {
        if (isCancelled) throw CancellationException("Sync stopped")
    }

    fun attach(value: Closeable) {
        val closeNow = synchronized(monitor) {
            if (isCancelled) true else { connection = value; false }
        }
        if (closeNow) runCatching { value.close() }
        check()
    }

    fun cancel() {
        val value = synchronized(monitor) {
            cancelled = true
            connection.also { connection = null }
        }
        runCatching { value?.close() }
    }
}
