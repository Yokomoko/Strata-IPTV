package com.strata.tv.ui.widgets

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.strata.tv.ui.theme.StrataColors

/**
 * Skeleton shimmer rail -- shown while data is loading.
 *
 * Renders [count] placeholder cards with a horizontal shimmer sweep
 * that loops infinitely.  Deliberately lightweight: no images, no
 * state objects, just a gradient animation on a remembered transition.
 */
@Composable
fun ShimmerRail(
    modifier: Modifier = Modifier,
    count: Int = 6,
    cardSize: DpSize = DpSize(140.dp, 210.dp),
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateX by transition.animateFloat(
        initialValue = -300f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-translate",
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            StrataColors.ShimmerBase,
            StrataColors.ShimmerHighlight,
            StrataColors.ShimmerBase,
        ),
        start = Offset(translateX, 0f),
        end = Offset(translateX + 300f, 0f),
    )

    Column(modifier = modifier) {
        // Placeholder title bar
        Row(modifier = Modifier.padding(start = 32.dp, top = 20.dp, bottom = 12.dp)) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 24.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(shimmerBrush),
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .height(20.dp)
                    .width(120.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(shimmerBrush),
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            userScrollEnabled = false,
        ) {
            items(count) {
                Box(
                    modifier = Modifier
                        .size(cardSize)
                        .clip(RoundedCornerShape(10.dp))
                        .background(shimmerBrush),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * Full-width shimmer block for hero / backdrop areas.
 */
@Composable
fun ShimmerHero(
    modifier: Modifier = Modifier,
    height: Int = 340,
) {
    val transition = rememberInfiniteTransition(label = "shimmer-hero")
    val translateX by transition.animateFloat(
        initialValue = -500f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-hero-translate",
    )

    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            StrataColors.ShimmerBase,
            StrataColors.ShimmerHighlight,
            StrataColors.ShimmerBase,
        ),
        start = Offset(translateX, 0f),
        end = Offset(translateX + 500f, 0f),
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .background(shimmerBrush),
    )
}
