package de.powerizzle.powerupgolf.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** A TCP connection carrying lines of text. Reads block, so call them on the IO dispatcher. */
class Link(private val socket: Socket) {
    private val reader = socket.getInputStream().bufferedReader()
    private val writer = socket.getOutputStream().bufferedWriter()

    init {
        // Small, frequent state updates: don't let Nagle batch them up.
        socket.tcpNoDelay = true
    }

    val remoteAddress: String get() = socket.inetAddress?.hostAddress.orEmpty()

    fun sendLine(line: String) {
        synchronized(writer) {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    /** Next line, or null when the other side closed the connection. */
    fun readLine(): String? = reader.readLine()

    fun close() {
        runCatching { socket.close() }
    }
}

/**
 * Runs a blocking call (accept, connect, receive) on the IO dispatcher. If the coroutine is cancelled,
 * [abort] is called to unblock it, typically by closing the socket.
 */
suspend fun <T> cancellableBlocking(abort: () -> Unit, block: () -> T): T = coroutineScope {
    // Only abort when we were cancelled mid-call; a finished call must keep its socket open.
    val finished = AtomicBoolean(false)
    val watcher = launch {
        try {
            awaitCancellation()
        } finally {
            if (!finished.get()) abort()
        }
    }
    try {
        withContext(Dispatchers.IO) { block() }
    } finally {
        finished.set(true)
        watcher.cancel()
    }
}
