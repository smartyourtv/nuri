package com.app.nuri.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.3.1: an error report's summary line names the underlying error
 * type, so the in-app list (or a screenshot of it) is decisive. The
 * pre-v0.3.1 wording without a type stays available unchanged.
 */
class ErrorSummaryTest {

    @Test
    fun summaryNamesTheErrorTypeWhenKnown() {
        val s = CrashReport.errorSummary("Update schedule at startup", "IllegalStateException")
        assertTrue(s.contains("Update schedule at startup"))
        assertTrue(s.contains("IllegalStateException"))
    }

    @Test
    fun summaryWithoutTypeMatchesTheOriginalWording() {
        assertEquals(
            "Something in Nuri went wrong, but Nuri kept running. (Where: Update schedule at startup.)",
            CrashReport.errorSummary("Update schedule at startup")
        )
        assertEquals(
            CrashReport.errorSummary("Update schedule at startup"),
            CrashReport.errorSummary("Update schedule at startup", null)
        )
        assertEquals(
            CrashReport.errorSummary("Update schedule at startup"),
            CrashReport.errorSummary("Update schedule at startup", "  ")
        )
    }

    @Test
    fun reportBodyCarriesClassMessageAndStack() {
        val report = CrashReport(
            epochMillis = 0, kind = ReportKind.ERROR, summaryLine = "s",
            flavor = "private", versionName = "0.3.1", versionCode = 4,
            deviceModel = "m", androidRelease = "12", sdkInt = 31,
            exceptionClass = "java.lang.IllegalStateException",
            exceptionMessage = "boom",
            stackTrace = "java.lang.IllegalStateException: boom\n\tat x.y(Z.kt:1)",
        )
        val body = ReportFormatter.format(report)
        assertTrue(body.contains("What happened: java.lang.IllegalStateException: boom"))
        assertTrue(body.contains("at x.y(Z.kt:1)"))
    }
}
