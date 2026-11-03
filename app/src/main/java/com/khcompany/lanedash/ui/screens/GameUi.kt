package com.khcompany.lanedash.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.khcompany.lanedash.ui.components.GameIcon
import com.khcompany.lanedash.ui.components.vector

private const val WEB_PLAY_URL = "https://kwanghyeon-cpu.github.io/LaneDash/web/"
private const val STORE_URL = "https://play.google.com/store/apps/details?id=com.khcompany.lanedash"

internal fun shareScore(context: Context, score: Int, best: Int, cityName: String) {
    val text = "🚗💨 Lane Dash — ${cityName}에서 ${score}점! (내 최고 ${best}점)\n" +
        "웹에서 바로 플레이 → $WEB_PLAY_URL\n" +
        "Google Play → $STORE_URL"
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "점수 공유하기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
internal fun FuelGauge(fuelFraction: () -> Float, modifier: Modifier = Modifier) {
    val fraction = fuelFraction().coerceIn(0f, 1f)
    val barColor = when {
        fraction > 0.5f -> Color(0xFF4CAF50)
        fraction > 0.2f -> Color(0xFFFFC93C)
        else -> Color(0xFFE63946)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        HudIconText(
            icon = GameIcon.GAS_PUMP,
            text = "FUEL",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyLarge,
            iconSize = 16,
        )
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .width(180.dp)
                .height(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(RoundedCornerShape(8.dp))
                    .background(barColor),
            )
        }
    }
}

@Composable
internal fun LaneButton(icon: GameIcon, description: String, onClick: () -> Unit, size: Dp = 56.dp) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        ),
        modifier = Modifier.size(size),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = icon.vector,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

/** Small leading icon + bold label, used inside buttons. */
@Composable
internal fun ButtonLabel(icon: GameIcon, text: String, color: Color = Color.Unspecified) {
    val tint = if (color == Color.Unspecified) LocalContentColor.current else color
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(imageVector = icon.vector, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(text = text, fontWeight = FontWeight.Bold, color = color)
    }
}

/** HUD readout: icon + text in one color. */
@Composable
internal fun HudIconText(icon: GameIcon, text: String, color: Color, style: androidx.compose.ui.text.TextStyle, iconSize: Int, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        Icon(imageVector = icon.vector, contentDescription = null, tint = color, modifier = Modifier.size(iconSize.dp))
        Text(text = text, style = style, color = color)
    }
}
