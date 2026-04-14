package com.strata.tv.ui.widgets

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.graphicsLayer
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
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "card-scale",
    )

    val shape = RoundedCornerShape(10.dp)

    // Reserve padding so the scaled card + focus ring don't overlap
    // neighboring cards. The whole card (frame, image, text) grows.
    val focusPad = 8.dp

    Box(
        modifier = modifier
            .size(
                width = cardSize.width + focusPad * 2,
                height = cardSize.height + focusPad * 2,
            )
            .padding(focusPad),
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
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

            // Bottom gradient + text overlay — tall + dark to beat poster text
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.0f to Color.Transparent,
                                0.3f to Color.Black.copy(alpha = 0.5f),
                                1.0f to Color.Black.copy(alpha = 0.95f),
                            ),
                        ),
                    )
                    .padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 12.dp),
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 14.sp,
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = subtitle,
                        color = StrataColors.TextTertiary,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    } // outer Box
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
