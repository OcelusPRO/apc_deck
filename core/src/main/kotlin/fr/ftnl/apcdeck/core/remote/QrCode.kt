package fr.ftnl.apcdeck.core.remote

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR code prêt à dessiner en SVG : [size] modules de côté (marge comprise), [path] = un carré par module noir. */
class QrCode(val size: Int, val path: String) {
    companion object {
        fun of(text: String): QrCode {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
            val path = StringBuilder()
            for (y in 0 until matrix.height) {
                var x = 0
                while (x < matrix.width) {
                    if (!matrix[x, y]) { x++; continue }
                    val start = x
                    while (x < matrix.width && matrix[x, y]) x++
                    path.append("M$start ${y}h${x - start}v1h-${x - start}z") // une bande par suite de modules noirs
                }
            }
            return QrCode(matrix.width, path.toString())
        }
    }
}
