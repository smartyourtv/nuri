package com.app.nuri.remote.android

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** Renders the connect code as a bitmap (design §4.2). Pure ZXing; no camera involved on this side. */
object QrBitmap {
    fun render(content: String, sizePx: Int = 720): Bitmap? = try {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        bitmap
    } catch (_: Exception) {
        null
    }
}
