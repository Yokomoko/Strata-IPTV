package com.strata.tv.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
 * Phase 9 upgrade: 1.06x focus scale, 3dp violet ring + glow shadow,
 * bottom gradient overlay for legibility, and richer typography.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PosterCard(
    title: String,
    subtitle: String?,
    posterUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardSize: DpSize = DpSize(width = 140.dp, height = 210.dp),
    imageScale: ContentScale = ContentScale.Crop,
    onFocused: (() -> Unit)? = null,
) {
    var isFocused by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(10.dp)

    // Use padding to reserve space for the focus ring + shadow so the
    // layout doesn't shift. The card renders at (cardSize - padding)
    // and the ring/shadow fill the reserved space without clipping or
    // overlapping neighbors.
    val focusPad = 6.dp

    Card(
        onClick = onClick,
        modifier = modifier
            .size(
                width = cardSize.width + focusPad * 2,
                height = cardSize.height + focusPad * 2,
            )
            .padding(focusPad)
            .then(
                if (isFocused) Modifier
                    .shadow(8.dp, shape, ambientColor = StrataColors.FocusGlow, spotColor = StrataColors.FocusGlow)
                    .border(2.dp, StrataColors.FocusRing, shape)
                else Modifier,
            )
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) onFocused?.invoke()
            },
        shape = CardDefaults.shape(shape = shape),
        scale = CardDefaults.scale(focusedScale = 1.04f),
        colors = CardDefaults.colors(
            containerColor = StrataColors.SurfaceRaised,
            focusedContainerColor = StrataColors.SurfaceRaised,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = title,
                    contentScale = imageScale,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Initials(title)
            }

            // Bottom gradient + text overlay
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                        ),
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = StrataColors.TextTertiary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
