package com.app.nuri.shownuri.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import com.app.nuri.reports.android.CrashReports
import com.app.nuri.shownuri.GeminiCore
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud vision route (v0.3.2 Part D): Google Gemini free tier over
 * plain HTTPS — no SDK, no new dependencies. Implements the seam the
 * screen consults when [com.app.nuri.shownuri.VisionRouteSelector]
 * picks CLOUD_FIRST.
 *
 * The API key is the caregiver's own, read from the app's private
 * settings at call time. It travels ONLY as the x-goog-api-key header
 * (never in the URL), and failures are recorded with
 * [GeminiCore.failureLabel] — status/exception class only, never a
 * response body, never the key.
 *
 * Blocking — call off the main thread. Any failure returns null and
 * the caller falls back to the on-device answer; the senior never
 * sees an error screen for a cloud problem.
 */
class GeminiVisionGateway(private val apiKey: () -> String) {

    fun analyze(imageBytes: ByteArray): GeminiCore.CloudAnswer? {
        val key = apiKey().trim()
        if (key.isBlank()) return null
        val jpeg = downscaleToJpeg(imageBytes) ?: run {
            CrashReports.recordProblem("Show Ilona cloud look", "cloud look failed (image could not be prepared)")
            return null
        }
        val base64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val requestJson = GeminiCore.buildRequestJson(GeminiCore.buildPrompt(), base64)
        var status: Int? = null
        return try {
            val connection = (URL(GeminiCore.ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
            }
            connection.outputStream.use { it.write(requestJson.toByteArray(Charsets.UTF_8)) }
            status = connection.responseCode
            if (status != 200) {
                CrashReports.recordProblem("Show Ilona cloud look", GeminiCore.failureLabel(status, null))
                null
            } else {
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val answer = GeminiCore.parseResponse(body)
                if (answer == null) {
                    CrashReports.recordProblem("Show Ilona cloud look", "cloud look failed (unreadable reply)")
                }
                answer
            }
        } catch (t: Throwable) {
            CrashReports.recordProblem("Show Ilona cloud look", GeminiCore.failureLabel(status, t))
            null
        }
    }

    /** Shrink the capture for the wire: max dimension 1024, JPEG q80. The original photo never leaves the phone at full size. */
    private fun downscaleToJpeg(imageBytes: ByteArray): ByteArray? {
        val bitmap = runCatching { BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) }.getOrNull() ?: return null
        return try {
            val maxDim = maxOf(bitmap.width, bitmap.height)
            val scaled = if (maxDim > MAX_DIMENSION) {
                val ratio = MAX_DIMENSION.toFloat() / maxDim
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
            } else bitmap
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (scaled !== bitmap) scaled.recycle()
            out.toByteArray()
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS: Int = 10_000
        const val READ_TIMEOUT_MS: Int = 25_000
        const val MAX_DIMENSION: Int = 1024
        const val JPEG_QUALITY: Int = 80

        /** True when an active network with internet capability exists. Cheap; safe on the main thread. */
        fun networkAvailable(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
