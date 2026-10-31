package com.nuvio.tv.ui.screens.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

@Composable
internal fun RemoteConsoleQrCode(url: String) {
    val qr = remember(url) {
        runCatching {
            val width = 320
            val matrix = QRCodeWriter().encode(
                url, BarcodeFormat.QR_CODE, width, width,
                mapOf(EncodeHintType.MARGIN to 2)
            )
            val pixels = IntArray(width * width) { i ->
                if (matrix[i % width, i / width]) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE
            }
            Bitmap.createBitmap(width, width, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, width)
            }.asImageBitmap()
        }.getOrNull()
    }
    Column(modifier = Modifier.padding(12.dp)) {
        Text("Naskenuj QR kód telefonem a otevři konzoli")
        if (qr != null) {
            Image(
                bitmap = qr,
                contentDescription = "QR kód vzdálené diagnostiky",
                modifier = Modifier.size(240.dp).background(Color.White).padding(6.dp)
            )
        } else {
            Text("QR kód není dostupný")
        }
    }
}
