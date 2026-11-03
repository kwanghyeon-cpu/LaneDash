package com.khcompany.lanedash.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.khcompany.lanedash.game.CarOption
import com.khcompany.lanedash.game.GameCatalog
import com.khcompany.lanedash.ui.components.rememberOptionalSprite

@Composable
fun CarSelectScreen(
    selectedCarId: String,
    onSelect: (String) -> Unit,
    onStart: () -> Unit,
) {
    // The pickers follow the chosen mode's orientation (see LaneDashApp): two columns when tall.
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Text(
            text = "자동차 선택",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (portrait) 2 else 4),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(GameCatalog.cars) { car ->
                CarCard(
                    car = car,
                    selected = car.id == selectedCarId,
                    onClick = { onSelect(car.id) },
                )
            }
        }
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(52.dp),
        ) {
            Text(text = "게임 시작", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CarCard(car: CarOption, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        val sprite = rememberOptionalSprite(car.rearSpriteName)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.3f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.background),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (sprite != null) {
                    // The rear view, bottom-anchored, scaled to fit the card.
                    val boxH = size.height * 0.8f
                    val aspect = sprite.width.toFloat() / sprite.height.toFloat()
                    val drawH = if (boxH * aspect > size.width * 0.8f) size.width * 0.8f / aspect else boxH
                    val drawW = drawH * aspect
                    drawImage(
                        image = sprite,
                        dstOffset = IntOffset(((size.width - drawW) / 2f).toInt(), (size.height * 0.92f - drawH).toInt()),
                        dstSize = IntSize(drawW.toInt().coerceAtLeast(1), drawH.toInt().coerceAtLeast(1)),
                    )
                } else {
                    drawRect(car.bodyColor, Offset(size.width * 0.3f, size.height * 0.4f), Size(size.width * 0.4f, size.height * 0.45f))
                }
            }
        }
        Text(
            text = car.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = car.englishName,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
