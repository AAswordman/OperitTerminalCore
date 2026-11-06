package com.ai.assistance.operit.terminal

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class PtyWriteTimeoutException(bytesWritten: Int) : InterruptedIOException("PTY input timed out") {
    init { bytesTransferred = bytesWritten }
}

/**
 * Both directions use the same nonblocking PTY descriptor. The reader waits for output, while
 * each input write has one deadline. A coroutine timeout around FileOutputStream cannot stop
 * a kernel write, and toggling O_NONBLOCK only during a write would race the reader.
 */
internal class PtyIo(private val descriptor: FileDescriptor, private val writeTimeoutMs: Long = 3_000) {
    private val closed = AtomicBoolean(false)
    private val writeLock = ReentrantLock()
    private var inputGeneration = 0L

    init {
        val flags = Os.fcntlInt(descriptor, OsConstants.F_GETFL, 0)
        Os.fcntlInt(descriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val buffer = ByteArray(1)
            return if (read(buffer, 0, 1) == -1) -1 else buffer[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkRange(buffer, offset, length)
            if (length == 0) return 0
            while (true) {
                checkOpen()
                try {
                    val count = Os.read(descriptor, buffer, offset, length)
                    return if (count == 0) -1 else count
                } catch (error: ErrnoException) {
                    when (error.errno) {
                        OsConstants.EIO -> return -1 // The slave has closed.
                        OsConstants.EINTR -> continue
                        OsConstants.EAGAIN -> awaitReady(OsConstants.POLLIN, 100)
                        else -> throw IOException("PTY read failed", error)
                    }
                }
            }
        }

        override fun close() = closeDescriptor()
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            writeBytes(buffer, offset, length, writeLock.withLock { inputGeneration })
        }

        override fun close() = closeDescriptor()
    }

    fun interrupt() {
        // Cancel an unfinished paste before sending Ctrl+C. Its remaining bytes must not appear
        // at the next shell prompt after the interrupted process releases the input queue.
        val generation = writeLock.withLock { ++inputGeneration }
        writeBytes(byteArrayOf(0x03), 0, 1, generation)
    }

    private fun writeBytes(buffer: ByteArray, offset: Int, length: Int, generation: Long) {
        checkRange(buffer, offset, length)
        val deadline = System.nanoTime() + writeTimeoutMs * 1_000_000
        var written = 0
        while (written < length) {
            checkOpen()
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) throw PtyWriteTimeoutException(written)
            try {
                val count = writeLock.withLock {
                    if (generation != inputGeneration) throw InterruptedIOException("PTY input cancelled by interrupt")
                    Os.write(descriptor, buffer, offset + written, length - written)
                }
                if (count == 0) throw IOException("PTY write made no progress")
                written += count
            } catch (error: ErrnoException) {
                when (error.errno) {
                    OsConstants.EINTR -> continue
                    OsConstants.EAGAIN -> awaitReady(
                        OsConstants.POLLOUT,
                        minOf(100L, (remainingNanos + 999_999) / 1_000_000).toInt()
                    )
                    else -> throw IOException("PTY write failed", error)
                }
            }
        }
    }

    private fun awaitReady(events: Int, timeoutMs: Int) {
        checkOpen()
        val poll = StructPollfd().apply {
            fd = descriptor
            this.events = events.toShort()
        }
        try {
            Os.poll(arrayOf(poll), timeoutMs)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EINTR) throw IOException("PTY poll failed", error)
        }
        checkOpen()
        if (poll.revents.toInt() and OsConstants.POLLNVAL != 0) throw IOException("PTY descriptor is invalid")
    }

    private fun checkOpen() {
        if (closed.get()) throw IOException("PTY is closed")
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("PTY I/O interrupted")
    }

    private fun checkRange(buffer: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > buffer.size - length) throw IndexOutOfBoundsException()
    }

    private fun closeDescriptor() {
        if (closed.compareAndSet(false, true)) {
            try {
                Os.close(descriptor)
            } catch (error: ErrnoException) {
                throw IOException("Failed to close PTY", error)
            }
        }
    }
}
