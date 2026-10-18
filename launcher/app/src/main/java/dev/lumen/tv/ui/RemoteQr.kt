package dev.lumen.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.lumen.tv.remote.RemoteService
import java.net.Inet4Address

/** The phone remote's address, from the Chromecast's current IPv4 address. */
fun remoteUrl(context: Context): String? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val ip = connectivity.getLinkProperties(connectivity.activeNetwork)
        ?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        ?.hostAddress ?: return null
    return "http://$ip:${RemoteService.PORT}"
}

fun qrBitmap(text: String, size: Int = 360): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** Full-screen sheet with the QR; any key closes it (handled by the caller). */
@Composable
fun RemoteQrSheet() {
    val context = LocalContext.current
    val url = remember { remoteUrl(context) }
    val qr = remember(url) { url?.let { qrBitmap(it).asImageBitmap() } }
    Box(Modifier.fillMaxSize().background(Color(0xF2050507)), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(232.dp).background(Color.White, RoundedCornerShape(18.dp)).padding(16.dp)) {
                qr?.let { Image(it, contentDescription = "QR del mando", filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize()) }
            }
            Column(Modifier.padding(start = 48.dp)) {
                SectionLabel("Mando en el móvil")
                Text(
                    "Escanea con la cámara",
                    style = Type.display.copy(fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight(200)),
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text("Conéctate al WiFi de casa y luego «Añadir a pantalla de inicio».", style = Type.title.copy(fontWeight = FontWeight(300), color = Tone.secondary))
                Text(url ?: "Sin conexión", style = Type.body.copy(color = Tone.tertiary), modifier = Modifier.padding(top = 18.dp))
                Text("Pulsa cualquier botón para cerrar", style = Type.caption.copy(color = Tone.tertiary), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
