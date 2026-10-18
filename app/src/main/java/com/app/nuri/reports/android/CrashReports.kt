package com.app.nuri.reports.android

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.app.nuri.BuildConfig
import com.app.nuri.reports.CrashReport
import com.app.nuri.reports.FileReportStore
import com.app.nuri.reports.RepairStatus
import com.app.nuri.reports.ReportFormatter
import com.app.nuri.reports.ReportKind
import com.app.nuri.reports.StartupBreadcrumbs
import com.app.nuri.reports.StoredReport
import java.io.File
import java.util.concurrent.Executors

/**
 * Android facade for the crash & error report system (standing rule,
 * Shawn 2026-10-17). Installed from [com.app.nuri.NuriApplication]'s
 * attachBaseContext — the earliest app-controlled hook — so even a crash
 * during startup leaves its evidence:
 *   - a copy in the app's private report store (drives the in-app view),
 *   - a copy in the device Downloads folder (`Nuri-crash-<ts>.txt`).
 * Reports are NEVER uploaded anywhere by the app.
 *
 * Crash writes are SYNCHRONOUS on the crashing thread (the process is
 * dying; nothing may be queued). All other writes run on one background
 * thread. Every write is best-effort and independently guarded: one
 * failing surface never loses the other, and the handler ALWAYS
 * delegates to the previous default handler afterwards.
 */
object CrashReports {

    val breadcrumbs = StartupBreadcrumbs()

    @Volatile private var appContext: Context? = null
    @Volatile private var store: FileReportStore? = null

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nuri-reports").apply { isDaemon = true }
    }

    /** Install once, as early as possible. Later calls are no-ops. */
    fun install(context: Context) {
        if (appContext != null) return
        appContext = context
        store = FileReportStore(File(context.filesDir, "crash-reports"))
        breadcrumbs.mark("crash reporting installed")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(NuriCrashHandler(previous))
    }

    fun contextOrNull(): Context? = appContext

    fun storeOrNull(): FileReportStore? = store

    // --- crash path (synchronous, crashing thread) ---------------------

    internal fun writeCrashNow(thread: Thread, throwable: Throwable) {
        try {
            breadcrumbs.mark("crash on thread '${thread.name}'")
            val report = buildReport(
                kind = ReportKind.CRASH,
                summaryLine = CrashReport.crashSummary(),
                throwable = throwable
            )
            persist(report)
        } catch (_: Throwable) {
            // The reporter must never make a crash worse.
        }
    }

    // --- non-fatal paths (background thread) ----------------------------

    /** A caught exception in a risky path (update executor, backup crypto, …). */
    fun recordError(source: String, throwable: Throwable) {
        io.execute {
            try {
                persist(
                    buildReport(
                        kind = ReportKind.ERROR,
                        summaryLine = CrashReport.errorSummary(source),
                        throwable = throwable
                    )
                )
            } catch (_: Throwable) { }
        }
    }

    /** A failure that arrived as a result value rather than an exception. */
    fun recordProblem(source: String, detail: String) {
        io.execute {
            try {
                persist(
                    buildReport(
                        kind = ReportKind.ERROR,
                        summaryLine = CrashReport.errorSummary(source),
                        throwable = null,
                        detail = detail
                    )
                )
            } catch (_: Throwable) { }
        }
    }

    /** A self-repair outcome — every repair is logged, success or failure. */
    fun recordRepair(what: String, evidence: String, status: RepairStatus) {
        io.execute {
            try {
                persist(
                    buildReport(
                        kind = ReportKind.REPAIR,
                        summaryLine = CrashReport.repairSummary(what, status),
                        throwable = null,
                        detail = evidence
                    )
                )
            } catch (_: Throwable) { }
        }
    }

    // --- in-app view support -------------------------------------------

    fun listReports(): List<StoredReport> = store?.list() ?: emptyList()

    fun readReport(report: StoredReport): String =
        store?.read(report.file) ?: "(this report could not be read)"

    fun deleteReport(report: StoredReport) {
        val name = report.file.name
        store?.delete(report.file)
        val ctx = appContext ?: return
        io.execute { DownloadsReportWriter.deleteByName(ctx, name) }
    }

    // --- internals -------------------------------------------------------

    private fun buildReport(
        kind: ReportKind,
        summaryLine: String,
        throwable: Throwable?,
        detail: String? = null
    ): CrashReport = CrashReport(
        epochMillis = System.currentTimeMillis(),
        kind = kind,
        summaryLine = summaryLine,
        flavor = BuildConfig.FLAVOR,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        deviceModel = Build.MODEL ?: "unknown",
        androidRelease = Build.VERSION.RELEASE ?: "unknown",
        sdkInt = Build.VERSION.SDK_INT,
        exceptionClass = throwable?.javaClass?.name,
        exceptionMessage = throwable?.message,
        stackTrace = throwable?.let { CrashReport.stackTraceOf(it) },
        detail = detail,
        breadcrumbs = breadcrumbs.snapshot()
    )

    /** Save to the app store AND to Downloads; each leg guarded separately. */
    private fun persist(report: CrashReport) {
        val content = ReportFormatter.format(report)
        var savedName: String? = null
        try {
            // The store returns the file it wrote, so the Downloads copy
            // carries the same (collision-proofed) name.
            savedName = store?.save(content, report.epochMillis)?.name
        } catch (_: Throwable) { }
        try {
            val ctx = appContext
            if (ctx != null) {
                DownloadsReportWriter.write(
                    ctx,
                    savedName ?: ReportFormatter.fileNameFor(report.epochMillis),
                    content
                )
            }
        } catch (_: Throwable) { }
    }
}

/**
 * The uncaught-exception handler: writes the report synchronously, then
 * hands the crash back to the previous handler (which ends the process).
 * It never swallows a crash and never throws.
 */
class NuriCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            CrashReports.writeCrashNow(thread, throwable)
        } catch (_: Throwable) { }
        if (previous != null && previous !== this) {
            previous.uncaughtException(thread, throwable)
        } else {
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(10)
        }
    }
}

/**
 * MediaStore Downloads writer. minSdk is 29, so scoped storage applies:
 * files the app creates in Downloads need no permission, and can be
 * updated/deleted by the app later. IS_PENDING keeps a half-written file
 * invisible until it is complete.
 */
object DownloadsReportWriter {

    fun write(context: Context, fileName: String, content: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            val written = resolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                true
            } ?: false
            if (!written) {
                runCatching { resolver.delete(uri, null, null) }
                return false
            }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            true
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    fun deleteByName(context: Context, fileName: String): Boolean {
        val resolver = context.contentResolver
        return try {
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            var deleted = false
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection, selection, arrayOf(fileName), null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                    )
                    if (resolver.delete(uri, null, null) > 0) deleted = true
                }
            }
            deleted
        } catch (_: Exception) {
            false
        }
    }
}
