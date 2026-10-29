package com.khcompany.lanedash.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khcompany.lanedash.game.GameMode
import com.khcompany.lanedash.ui.components.rememberOptionalSprite

/**
 * Portrait screen: the title and this mode picker are always portrait; the screens after it follow
 * the chosen mode (classic is landscape, highway is portrait) — see LaneDashApp.
 */
@Composable
fun ModeSelectScreen(
    selectedMode: GameMode,
    onSelect: (GameMode) -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "게임 모드",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ModeCard(
                title = "클래식",
                tag = "가로 화면",
                lines = listOf("5개 차선을 위·아래로 오가며 장애물을 피해요", "연료, 아이템, 근접 회피 보너스"),
                previewSprites = listOf("car_gen1"),
                selected = selectedMode == GameMode.CLASSIC,
                onClick = { onSelect(GameMode.CLASSIC) },
                modifier = Modifier.weight(1f),
            )
            ModeCard(
                title = "하이웨이",
                tag = "세로 화면 · NEW",
                lines = listOf("차 뒤에서 바라보는 아웃런 스타일", "5차선 도로를 좌우로 조향하며 커브를 달려요"),
                previewSprites = listOf("car_rear_1"),
                selected = selectedMode == GameMode.HIGHWAY,
                onClick = { onSelect(GameMode.HIGHWAY) },
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(56.dp),
        ) {
            Text(text = "다음: 도시 선택", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    tag: String,
    lines: List<String>,
    previewSprites: List<String>,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        for (line in lines) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }
        // A peek at the actual art, so the two modes are told apart at a glance.
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.background)
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (name in previewSprites) {
                val sprite = rememberOptionalSprite(name)
                Box(modifier = Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (sprite != null) {
                        Image(
                            bitmap = sprite,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}
