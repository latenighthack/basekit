package com.latenighthack.basekit.viewmodel.tui

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Process-global, thread-safe sink for the debug log lines shown in the TUI's bottom log window.
 *
 * Any code in the process can append a line with [log]. While the TUI runs, [install] additionally
 * redirects `System.out`/`System.err` here, so stray prints (including from third-party libraries)
 * are captured for debugging instead of corrupting the rendered screen — TamboUI paints through the
 * jline terminal, not `System.out`, so nothing else competes for it. [uninstall] restores the original
 * streams. The buffer is a bounded ring: only the most recent [CAPACITY] lines are retained.
 */
public object TuiLog {
    /** Maximum retained lines; the oldest are dropped as new ones arrive. */
    private const val CAPACITY: Int = 1000

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    // The System.out/System.err in effect before install(); null when not installed. Guarded by lock.
    private var savedOut: PrintStream? = null
    private var savedErr: PrintStream? = null

    // Optional full-history mirror: the bottom window only shows a handful of ellipsized lines, so
    // captured output is also teed here (untruncated) for `cat`-ing after a run. Guarded by lock.
    private var fileOut: PrintStream? = null

    /** Where [install] tees captured output: $TUI_LOG_FILE, or <tmp>/basekit-tui.log by default. */
    public val logFilePath: String =
        System.getenv("TUI_LOG_FILE") ?: File(System.getProperty("java.io.tmpdir"), "basekit-tui.log").absolutePath

    /** Appends [message] (split on newlines, each line timestamped) to the buffer. Safe from any thread. */
    public fun log(message: String) {
        val stamp = LocalTime.now().format(timeFormat)
        synchronized(lock) {
            for (line in message.split('\n')) {
                val stamped = "$stamp  $line"
                lines.addLast(stamped)
                while (lines.size > CAPACITY) lines.removeFirst()
                fileOut?.println(stamped)
            }
        }
    }

    /** The most recent [max] lines, oldest first — the tail rendered in the log window. */
    public fun tail(max: Int): List<String> = synchronized(lock) {
        if (max <= 0) return emptyList()
        val from = (lines.size - max).coerceAtLeast(0)
        lines.subList(from, lines.size).toList()
    }

    /** Redirects `System.out`/`System.err` into this buffer. Idempotent; pair with [uninstall]. */
    public fun install() {
        synchronized(lock) {
            if (savedOut != null) return
            savedOut = System.out
            savedErr = System.err
            // Truncate a fresh file per session; failure to open must not break the TUI.
            fileOut = runCatching {
                PrintStream(FileOutputStream(logFilePath, false), true, StandardCharsets.UTF_8)
            }.getOrNull()
        }
        // setOut/setErr run outside the lock: the redirected streams call log(), which takes it.
        System.setOut(PrintStream(LineBufferedStream { log(it) }, true, StandardCharsets.UTF_8))
        System.setErr(PrintStream(LineBufferedStream { log(it) }, true, StandardCharsets.UTF_8))
        log("TuiLog: teeing captured output to $logFilePath")
    }

    /** Restores the `System.out`/`System.err` streams captured by [install]. Idempotent. */
    public fun uninstall() {
        val out: PrintStream?
        val err: PrintStream?
        synchronized(lock) {
            out = savedOut
            err = savedErr
            savedOut = null
            savedErr = null
            fileOut?.close()
            fileOut = null
        }
        out?.let { System.setOut(it) }
        err?.let { System.setErr(it) }
    }
}

/**
 * An [OutputStream] that accumulates bytes and emits one string per completed line (on `\n`), so a
 * captured [PrintStream] turns both `print` and `println` into whole-line log entries. Carriage
 * returns are dropped so CRLF output leaves no stray characters.
 */
private class LineBufferedStream(private val sink: (String) -> Unit) : OutputStream() {
    private val buffer = ByteArrayOutputStream(128)

    @Synchronized
    override fun write(b: Int) {
        when (b) {
            '\n'.code -> {
                sink(buffer.toString(StandardCharsets.UTF_8))
                buffer.reset()
            }
            '\r'.code -> Unit
            else -> buffer.write(b)
        }
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        var i = off
        val end = off + len
        while (i < end) {
            write(b[i].toInt() and 0xFF)
            i++
        }
    }
}
