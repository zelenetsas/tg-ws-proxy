package com.flowseal.tgwsproxy.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.flowseal.tgwsproxy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import qrcode.QRCode

/**
 * QR-код ссылки tg://proxy — самый быстрый способ подключить Telegram,
 * в том числе на другом устройстве (навести камеру в мессенджере).
 *
 * Рендер через qrcode-kotlin-android: renderToBytes("PNG") -> BitmapFactory,
 * без привязки к внутренним Writer-типам библиотеки.
 */
@Composable
fun QrCodeCard(link: String, onClick: (() -> Unit)? = null) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = link) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val png = QRCode(link, QR_SQUARE_SIZE).renderToBytes("PNG")
                BitmapFactory.decodeByteArray(png, 0, png.size)
            }.getOrNull()
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.qr_content_desc),
                    modifier = Modifier.size(220.dp),
                )
            } else {
                Box(Modifier.size(220.dp).background(Color.White))
            }
        }
        Text(
            stringResource(R.string.qr_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private const val QR_SQUARE_SIZE = 12
