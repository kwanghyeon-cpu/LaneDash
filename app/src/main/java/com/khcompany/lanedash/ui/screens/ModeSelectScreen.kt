package com.khcompany.lanedash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khcompany.lanedash.game.GameMode

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
            .padding(16.dp),
    ) {
        Text(
            text = "게임 모드",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ModeCard(
                title = "클래식",
                tag = "가로 화면",
                lines = listOf("5개 차선을 위·아래로 오가며 장애물을 피해요", "연료, 아이템, 근접 회피 보너스"),
                selected = selectedMode == GameMode.CLASSIC,
                onClick = { onSelect(GameMode.CLASSIC) },
                modifier = Modifier.weight(1f),
            )
            ModeCard(
                title = "하이웨이",
                tag = "세로 화면 · NEW",
                lines = listOf("뒤에서 바라보는 아웃런 스타일", "왼쪽/오른쪽을 눌러 조향하고 커브를 달려요", "폰을 세로로 잡아 주세요"),
                selected = selectedMode == GameMode.HIGHWAY,
                onClick = { onSelect(GameMode.HIGHWAY) },
                modifier = Modifier.weight(1f),
            )
        }
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(52.dp),
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
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(18.dp),
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
            modifier = Modifier.padding(bottom = 6.dp),
        )
        for (line in lines) {
            Text(
                text = line,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 14.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }
    }
}
