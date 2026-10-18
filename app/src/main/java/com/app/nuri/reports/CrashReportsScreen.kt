package com.app.nuri.reports

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.app.nuri.reports.android.CrashReports
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Crash & error reports" — the in-app half of the standing crash-report
 * rule (Shawn, 2026-10-17): list, read, share, delete. The same reports
 * also live as files in the Downloads folder; these are Nuri's own copies,
 * so the view works even if a Downloads file was deleted.
 *
 * Readability law: every text here uses the theme typography (body 20sp,
 * headings 28sp, button labels 22sp — enforced centrally in NuriText).
 * Reports are never sent anywhere by Nuri; sharing is the user's tap.
 */
@Composable
fun CrashReportsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<StoredReport>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var openReport by remember { mutableStateOf<StoredReport?>(null) }
    var openText by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            reports = withContext(Dispatchers.IO) { CrashReports.listReports() }
            loaded = true
        }
    }
    LaunchedEffect(Unit) { reload() }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Crash & error reports", style = MaterialTheme.typography.headlineMedium)
        val current = openReport
        if (current == null) {
            Text(
                "If Nuri ever has a problem, it saves a report here and as a file in the " +
                    "Downloads folder. Reports never leave this phone unless you share one."
            )
            if (feedback.isNotEmpty()) Text(feedback, style = MaterialTheme.typography.titleMedium)
            when {
                !loaded -> Text("Looking for reports…")
                reports.isEmpty() -> Text("There are no reports. If Nuri has a problem, a report will appear here.")
                else -> reports.forEach { report ->
                    Text(report.summaryLine, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Saved: ${SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.US).format(Date(report.epochMillis))}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                openText = withContext(Dispatchers.IO) { CrashReports.readReport(report) }
                                openReport = report
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Read this report") }
                }
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back to Settings") }
        } else {
            Text(openText, style = MaterialTheme.typography.bodyLarge)
            val context = LocalContext.current
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Nuri report — ${current.file.name}")
                        putExtra(Intent.EXTRA_TEXT, openText)
                    }
                    context.startActivity(Intent.createChooser(send, "Share this report"))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Share this report") }
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { CrashReports.deleteReport(current) }
                        openReport = null
                        feedback = "That report was deleted, here and from the Downloads folder."
                        reload()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Delete this report") }
            Button(
                onClick = { openReport = null },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Back to the list of reports") }
        }
    }
}
