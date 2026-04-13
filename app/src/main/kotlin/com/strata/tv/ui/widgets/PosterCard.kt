package com.strata.tv.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.strata.tv.ui.theme.StrataColors

/**
 * Polished poster card for movies / box sets / continue-watching.
 *
 * Wraps Compose for TV's [Card] — gives us the focus-scale animation,
 * glow shadow, rounded corners, and accessibility behaviour for free.
 * Image loading goes through Coil 3 with on-disk caching.
 *
 * Falls back to a coloured tile + initials when there's no poster URL.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterCard(
    title: String,
    subtitle: String?,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardSize: DpSize = DpSize(width = 140.dp, height = 252.dp),
    onFocused: (() -> Unit)? = null,
) {
    val imageHeight = cardSize.height - 56.dp
    Card(
        onClick = onClick,
        modifier = modifier
            .size(width = cardSize.width, height = cardSize.height)
            .let { mod ->
                if (onFocused != null) {
                    mod.onFocusChanged { if (it.isFocused) onFocused() }
                } else {
                    mod
                }
            },
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = CardDefaults.colors(
            containerColor = StrataColors.SurfaceRaised,
        ),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(imageHeight),
            ) {
                if (!posterUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = posterUrl,
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Initials(title)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                color = StrataColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = StrataColors.TextTertiary,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** Coloured tile + initials fallback when no poster URL is available. */
@Composable
private fun Initials(title: String) {
    val tileColor = remember(title) {
        val palette = listOf(
            Color(0xFF3F2A56), Color(0xFF1F4068), Color(0xFF4F2828),
            Color(0xFF1B5E20), Color(0xFF4A148C), Color(0xFF263238),
            Color(0xFF5D4037), Color(0xFF1A237E),
        )
        palette[(title.hashCode() and Int.MAX_VALUE) % palette.size]
    }
    Box(
        modifier = Modifier.fillMaxSize().background(tileColor),
        contentAlignment = Alignment.Center,
    ) {
        val letters = title.trim().split(Regex("\\s+")).take(2)
            .joinToString("") { it.firstOrNull()?.uppercaseChar()?.toString() ?: "" }
            .ifEmpty { "?" }
        Text(
            text = letters,
            color = StrataColors.TextPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
