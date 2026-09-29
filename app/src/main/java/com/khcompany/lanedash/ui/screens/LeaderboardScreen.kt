package com.khcompany.lanedash.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khcompany.lanedash.game.LeaderboardEntry
import com.khcompany.lanedash.game.LeaderboardRepository

@Composable
fun LeaderboardScreen(
    repository: LeaderboardRepository,
    highlightName: String?,
    highlightScore: Int?,
    onHome: () -> Unit,
) {
    var entries by remember { mutableStateOf<List<LeaderboardEntry>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        entries = try {
            repository.fetchTopScores(10)
        } catch (e: Exception) {
            android.util.Log.e("Leaderboard", "fetchTopScores failed", e)
            loadFailed = true
            emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
    ) {
        Text(
            text = "TOP 10",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "전체 순위",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 16.dp),
        )

        val currentEntries = entries
        if (currentEntries == null) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = "불러오는 중...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        } else if (currentEntries.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = if (loadFailed) {
                        "순위표를 불러오지 못했어요.\n네트워크 연결을 확인해주세요."
                    } else {
                        "아직 기록이 없어요.\n첫 번째 기록을 남겨보세요!"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(currentEntries.size) { index ->
                    val entry = currentEntries[index]
                    val isHighlighted = highlightName != null &&
                        entry.name == highlightName &&
                        entry.score == highlightScore
                    LeaderboardRow(rank = index + 1, entry = entry, highlighted = isHighlighted)
                }
            }
        }

        Button(
            onClick = onHome,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(52.dp),
        ) {
            Text(text = "홈으로", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LeaderboardRow(rank: Int, entry: LeaderboardEntry, highlighted: Boolean) {
    val medalColor = when (rank) {
        1 -> androidx.compose.ui.graphics.Color(0xFFFFD166)
        2 -> androidx.compose.ui.graphics.Color(0xFFC9CDD6)
        3 -> androidx.compose.ui.graphics.Color(0xFFCC8A4D)
        else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "#$rank",
            style = MaterialTheme.typography.titleLarge,
            color = medalColor,
            modifier = Modifier.padding(end = 16.dp),
        )
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = entry.score.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
