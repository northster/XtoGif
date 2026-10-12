package io.github.northster.xsaver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.northster.xsaver.core.MediaKind
import java.util.Locale

fun formatSeconds(sec: Double): String {
    val total = sec.coerceAtLeast(0.0)
    val m = (total / 60).toInt()
    val s = total - m * 60
    return if (m > 0) String.format(Locale.US, "%d:%04.1f", m, s) else String.format(Locale.US, "%.1f초", s)
}

fun kindLabel(kind: MediaKind) = when (kind) {
    MediaKind.PHOTO -> "사진"
    MediaKind.VIDEO -> "영상"
    MediaKind.GIF -> "GIF"
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier
            .background(Color(0xAA000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}
