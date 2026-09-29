package com.khcompany.lanedash.ui.screens

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khcompany.lanedash.game.CityTheme
import com.khcompany.lanedash.game.GameCatalog
import com.khcompany.lanedash.ui.components.drawCitySkyline
import com.khcompany.lanedash.ui.components.drawCitySkylineSprite
import com.khcompany.lanedash.ui.components.rememberOptionalSprite

@Composable
fun CitySelectScreen(
    selectedCityId: String,
    onSelect: (String) -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        Text(
            text = "도시 선택",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(GameCatalog.cities) { city ->
                CityCard(
                    city = city,
                    selected = city.id == selectedCityId,
                    onClick = { onSelect(city.id) },
                )
            }
        }
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(52.dp),
        ) {
            Text(text = "다음: 자동차 선택", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CityCard(city: CityTheme, selected: Boolean, onClick: () -> Unit) {
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
        val sprite = rememberOptionalSprite(city.spriteName)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.3f)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val horizonY = size.height * 0.82f
                if (sprite != null) {
                    drawCitySkylineSprite(
                        sprite = sprite,
                        horizonY = horizonY,
                        parallaxUnits = 0f,
                        pxPerUnit = size.width / 10f,
                    )
                } else {
                    drawRect(brush = Brush.verticalGradient(listOf(city.skyTop, city.skyBottom)))
                    drawCitySkyline(
                        city = city,
                        worldWidthUnits = 10f,
                        horizonY = horizonY,
                        parallaxUnits = 0f,
                    )
                }
                drawRect(color = city.roadColor, topLeft = Offset(0f, horizonY), size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.18f))
            }
        }
        Text(
            text = city.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = city.englishName,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
