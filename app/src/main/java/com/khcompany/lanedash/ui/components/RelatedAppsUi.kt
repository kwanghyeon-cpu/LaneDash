package com.khcompany.lanedash.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.khcompany.lanedash.related.RelatedApp
import com.khcompany.lanedash.related.RelatedAppsRepository

/** Sibling apps for this screen; empty until loaded (and if there are none). */
@Composable
fun rememberRelatedApps(): List<RelatedApp> {
    val context = LocalContext.current
    val apps by produceState(initialValue = emptyList<RelatedApp>()) {
        value = runCatching { RelatedAppsRepository.siblings(context) }.getOrDefault(emptyList())
    }
    return apps
}

/** "함께 즐기면 좋은 게임" card: icon, name and one-line pitch per sibling. Draws nothing when empty. */
@Composable
fun RelatedAppsCard(apps: List<RelatedApp>, modifier: Modifier = Modifier) {
    if (apps.isEmpty()) return
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "함께 즐기면 좋은 게임",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        apps.forEach { app ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { RelatedAppsRepository.open(context, app) },
            ) {
                RelatedAppIcon(app)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = app.tagline,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = GameIcon.OPEN_EXTERNAL.vector,
                    contentDescription = "열기",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** One quiet line pointing at the first sibling, e.g. "요리조리 런도 달려 보기". */
@Composable
fun RelatedAppLine(app: RelatedApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { RelatedAppsRepository.open(context, app) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = GameIcon.SNEAKER.vector,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "${app.name}도 달려 보기",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun RelatedAppIcon(app: RelatedApp) {
    val context = LocalContext.current
    var bitmap by remember(app.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app.id) { bitmap = RelatedAppsRepository.icon(context, app) }
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(bitmap = b, contentDescription = null, modifier = Modifier.size(44.dp))
        } else {
            Icon(
                imageVector = GameIcon.SNEAKER.vector,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
