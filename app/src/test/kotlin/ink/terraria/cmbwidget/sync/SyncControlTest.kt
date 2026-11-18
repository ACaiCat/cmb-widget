package ink.terraria.cmbwidget.sync

import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SyncControlTest {
    @Test fun cancelClosesAnAttachedConnectionOnlyOnce() {
        val closes = AtomicInteger()
        val control = SyncControl()
        control.attach(Closeable { closes.incrementAndGet() })
        control.cancel()
        control.cancel()
        assertEquals(1, closes.get())
        assertThrows(CancellationException::class.java) { control.check() }
    }

    @Test fun cancelledWorkClosesAConnectionAttachedLater() {
        val closes = AtomicInteger()
        val control = SyncControl()
        control.cancel()
        assertThrows(CancellationException::class.java) {
            control.attach(Closeable { closes.incrementAndGet() })
        }
        assertEquals(1, closes.get())
    }

    @Test fun schedulerStopClosesAConnectionBeforeItCanConnect() {
        val control = SyncControl { true }
        val socket = Socket()
        assertThrows(CancellationException::class.java) { control.attach(socket) }
        assertTrue(socket.isClosed)
    }

    @Test fun cancelledWorkCannotBeginAnImapConnection() {
        val control = SyncControl()
        control.cancel()
        assertThrows(CancellationException::class.java) {
            ink.terraria.cmbwidget.mail.ImapClient("unreachable.invalid", 993, "test", "test", control::attach)
        }
    }

    @Test fun cancellationUnblocksAnActualSocketRead() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0).use { server ->
                Socket("127.0.0.1", server.localPort).use { socket ->
                    server.accept().use {
                        val control = SyncControl()
                        control.attach(socket)
                        val reading = CountDownLatch(1)
                        val result = executor.submit<Boolean> {
                            reading.countDown()
                            try { socket.getInputStream().read(); false }
                            catch (_: java.io.IOException) { true }
                        }
                        assertTrue(reading.await(2, TimeUnit.SECONDS))
                        control.cancel()
                        assertTrue(result.get(2, TimeUnit.SECONDS))
                    }
                }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun attachingDuringCancellationNeverLeaksTheConnection() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val closes = AtomicInteger()
                val control = SyncControl()
                val start = CountDownLatch(1)
                val attach = executor.submit {
                    start.await()
                    try { control.attach(Closeable { closes.incrementAndGet() }) }
                    catch (_: CancellationException) { }
                }
                val cancel = executor.submit { start.await(); control.cancel() }
                start.countDown()
                attach.get(2, TimeUnit.SECONDS)
                cancel.get(2, TimeUnit.SECONDS)
                assertEquals(1, closes.get())
            }
        } finally { executor.shutdownNow() }
    }
}
