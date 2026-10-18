package com.app.nuri.shownuri.android

import android.graphics.BitmapFactory
import com.app.nuri.shownuri.VisionGateway
import com.app.nuri.shownuri.VisionLabel
import com.app.nuri.shownuri.VisionResult
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/**
 * On-device vision route (Show Nuri): ML Kit bundled models — text
 * recognition (OCR) + image labeling. Nothing leaves the phone on this
 * route. It is the route this build ships; per Shawn's 2026-10-17 22:13
 * decision the [VisionGateway] seam accepts additional routes freely
 * when the app gains a cloud path — none is configured in this build,
 * and this class never pretends otherwise.
 */
class MlKitVisionGateway : VisionGateway {
    override val routeName: String = "On this phone (ML Kit)"
    override fun isAvailable(): Boolean = true

    override fun analyze(imageBytes: ByteArray): VisionResult? {
        val bitmap = runCatching { BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) }.getOrNull() ?: return null
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build())
        val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
        return try {
            val text = runCatching { Tasks.await(recognizer.process(image), 20, TimeUnit.SECONDS).text }.getOrDefault("")
            val labels = runCatching {
                Tasks.await(labeler.process(image), 20, TimeUnit.SECONDS)
                    .map { VisionLabel(it.text, it.confidence) }
            }.getOrDefault(emptyList())
            val qr = runCatching {
                Tasks.await(scanner.process(image), 20, TimeUnit.SECONDS).firstOrNull()?.rawValue
            }.getOrNull()
            VisionResult(ocrText = text, labels = labels, qrPayload = qr, routeName = routeName)
        } finally {
            runCatching { recognizer.close() }; runCatching { labeler.close() }; runCatching { scanner.close() }
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /** Mean luminance + a cheap sharpness proxy from sampled pixels (for retake coaching). */
    fun quality(imageBytes: ByteArray): Pair<Double?, Double?> {
        val bitmap = runCatching { BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) }.getOrNull() ?: return null to null
        return try {
            val w = bitmap.width; val h = bitmap.height
            if (w < 8 || h < 8) return null to null
            val stepX = maxOf(1, w / 48); val stepY = maxOf(1, h / 48)
            var sum = 0.0; var count = 0
            var prev = -1.0; var edgeSum = 0.0; var edgeCount = 0
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val px = bitmap.getPixel(x, y)
                    val lum = (0.299 * android.graphics.Color.red(px) + 0.587 * android.graphics.Color.green(px) + 0.114 * android.graphics.Color.blue(px)) / 255.0
                    sum += lum; count++
                    if (prev >= 0) { edgeSum += kotlin.math.abs(lum - prev); edgeCount++ }
                    prev = lum
                    x += stepX
                }
                y += stepY
            }
            val mean = if (count > 0) sum / count else null
            val sharp = if (edgeCount > 0) (edgeSum / edgeCount) * 4.0 else null // normalized: crisp text edges score well above blur
            mean to sharp
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
