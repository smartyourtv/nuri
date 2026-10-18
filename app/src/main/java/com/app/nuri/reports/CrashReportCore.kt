package com.app.nuri.reports

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash & error reports — pure core (standing rule, Shawn 2026-10-17,
 * retroactive): every app captures its errors and surfaces them TWO ways —
 * an in-app reports view and a report FILE in the device Downloads folder,
 * written at crash time. Reports are NEVER auto-uploaded anywhere; they
 * stay on the device until the user shares one.
 *
 * This file is the platform-free half: the startup breadcrumb trail, the
 * report model, the ONE formatter that defines the file format (name +
 * contents), and a java.io file store the app keeps in its private storage
 * so the in-app view works even if the Downloads copy is deleted. The
 * Android half (uncaught-exception handler, MediaStore writer, facade)
 * lives in reports/android/. JVM tests cover everything here.
 */

/**
 * A tiny trail of startup steps ("got as far as X") so a crash report shows
 * how far the app got before it died. Thread-safe; bounded so a long-lived
 * process cannot grow it forever. The clock is injected for tests.
 */
class StartupBreadcrumbs(private val clockMillis: () -> Long = { System.currentTimeMillis() }) {

    private data class Entry(val label: String, val atMillis: Long)

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private var startMillis: Long? = null

    fun mark(label: String) {
        synchronized(lock) {
            val now = clockMillis()
            if (startMillis == null) startMillis = now
            entries.add(Entry(label, now))
            while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        }
    }

    /** Lines like "+120 ms — content shown", relative to the first mark. */
    fun snapshot(): List<String> = synchronized(lock) {
        val start = startMillis ?: return emptyList()
        entries.map { "+${it.atMillis - start} ms — ${it.label}" }
    }

    fun clear() = synchronized(lock) { entries.clear(); startMillis = null }

    companion object { const val MAX_ENTRIES = 100 }
}

enum class ReportKind(val label: String) {
    CRASH("crash"),
    ERROR("error"),
    REPAIR("repair")
}

/** Outcome of a self-repair attempt (standing self-repair rule, 2026-10-17). */
enum class RepairStatus(val label: String) {
    REPAIRED("repaired"),
    REPAIR_FAILED("repair-failed")
}

/**
 * One report. [summaryLine] is the plain-language first line of the file —
 * the in-app list shows exactly this text. For repairs, [detail] carries
 * the evidence and the verification result.
 */
data class CrashReport(
    val epochMillis: Long,
    val kind: ReportKind,
    val summaryLine: String,
    val flavor: String,
    val versionName: String,
    val versionCode: Int,
    val deviceModel: String,
    val androidRelease: String,
    val sdkInt: Int,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val stackTrace: String? = null,
    val detail: String? = null,
    val breadcrumbs: List<String> = emptyList()
) {
    companion object {
        fun stackTraceOf(t: Throwable): String {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            return sw.toString().trimEnd()
        }

        /** Plain-language summary lines — the only wording source. */
        fun crashSummary(): String =
            "Nuri stopped unexpectedly. This report was saved so the problem can be found and fixed."
        fun errorSummary(source: String, exceptionSimpleName: String? = null): String =
            "Something in Nuri went wrong, but Nuri kept running. (Where: $source.)" +
                // v0.3.1: name the underlying error type in the summary
                // itself (the full class + stack are in the report body),
                // so even the list view — or a screenshot of it — is
                // decisive evidence.
                (exceptionSimpleName?.takeIf { it.isNotBlank() }
                    ?.let { " (Error type: $it.)" } ?: "")
        fun repairSummary(what: String, status: RepairStatus): String = when (status) {
            RepairStatus.REPAIRED ->
                "Nuri found a problem in its own saved information and fixed it. ($what.)"
            RepairStatus.REPAIR_FAILED ->
                "Nuri found a problem in its own saved information, but could not fix it. ($what.)"
        }
    }
}

/**
 * The file format, defined once: name `Nuri-crash-<yyyyMMdd-HHmmss>.txt`
 * (local time — the standing rule's pattern), contents with the
 * plain-language summary as the first line.
 */
object ReportFormatter {

    private fun stamp(epochMillis: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(epochMillis))

    private fun readable(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMillis))

    fun fileNameFor(epochMillis: Long): String = "Nuri-crash-${stamp(epochMillis)}.txt"

    /**
     * When two reports land in the same second, later ones get "-2", "-3"…
     * before ".txt" so nothing overwrites evidence.
     */
    fun uniqueFileName(baseName: String, exists: (String) -> Boolean): String {
        if (!exists(baseName)) return baseName
        val stem = baseName.removeSuffix(".txt")
        var n = 2
        while (true) {
            val candidate = "$stem-$n.txt"
            if (!exists(candidate)) return candidate
            n++
        }
    }

    fun format(report: CrashReport): String {
        val sb = StringBuilder()
        sb.appendLine(report.summaryLine)
        sb.appendLine()
        sb.appendLine("Nuri crash & error report")
        sb.appendLine("When: ${readable(report.epochMillis)}")
        sb.appendLine("Kind: ${report.kind.label}")
        sb.appendLine("App: Nuri ${report.versionName} (version code ${report.versionCode}), ${report.flavor} edition")
        sb.appendLine("Phone: ${report.deviceModel}, Android ${report.androidRelease} (API ${report.sdkInt})")
        sb.appendLine()
        if (report.exceptionClass != null) {
            sb.appendLine("What happened: ${report.exceptionClass}${report.exceptionMessage?.let { ": $it" } ?: ""}")
            sb.appendLine()
        }
        sb.appendLine("How far Nuri got:")
        if (report.breadcrumbs.isEmpty()) {
            sb.appendLine("  (no steps were recorded)")
        } else {
            report.breadcrumbs.forEach { sb.appendLine("  $it") }
        }
        sb.appendLine()
        sb.appendLine("Technical details:")
        sb.appendLine(report.stackTrace ?: report.detail ?: "(none)")
        return sb.toString()
    }
}

/** A stored report file, as the in-app list shows it. */
data class StoredReport(
    val file: File,
    val summaryLine: String,
    val epochMillis: Long
)

/**
 * The app's own copies, in app-private storage (`filesDir/crash-reports`).
 * Plain files, so even a damaged settings store cannot take the evidence
 * down with it. Newest first; capped at [MAX_REPORTS] with the oldest
 * deleted first.
 */
class FileReportStore(private val dir: File) {

    fun save(content: String, epochMillis: Long): File {
        dir.mkdirs()
        val name = ReportFormatter.uniqueFileName(ReportFormatter.fileNameFor(epochMillis)) {
            File(dir, it).exists()
        }
        val file = File(dir, name)
        file.writeText(content)
        enforceRetention()
        return file
    }

    fun list(): List<StoredReport> {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("Nuri-crash-") && f.name.endsWith(".txt") }
            ?: return emptyList()
        return files.map { f ->
            val summary = try {
                f.bufferedReader().use { it.readLine() } ?: "(empty report)"
            } catch (_: Exception) {
                "(this report could not be read)"
            }
            StoredReport(f, summary, epochFromName(f.name) ?: f.lastModified())
        }.sortedByDescending { it.epochMillis }
    }

    fun read(file: File): String = file.readText()

    fun delete(file: File): Boolean = try { file.delete() } catch (_: Exception) { false }

    fun enforceRetention() {
        val all = list()
        if (all.size > MAX_REPORTS) {
            all.drop(MAX_REPORTS).forEach { delete(it.file) }
        }
    }

    companion object {
        const val MAX_REPORTS = 50

        private val NAME_STAMP = Regex("""Nuri-crash-(\d{8})-(\d{6})(?:-\d+)?\.txt""")

        fun epochFromName(name: String): Long? {
            val m = NAME_STAMP.matchEntire(name) ?: return null
            return try {
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US).parse("${m.groupValues[1]}${m.groupValues[2]}")?.time
            } catch (_: Exception) {
                null
            }
        }
    }
}
