package com.strata.tv.ui.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.strata.tv.ui.theme.StrataColors

/**
 * Holder for the home screen's "currently-featured" item.
 *
 * Cards register themselves via [setFeatured] when they receive
 * focus.  The [ImmersiveBackdrop] composable observes [current] and
 * crossfades the screen-wide background to the new item's backdrop.
 */
@Stable
class FeaturedState {
    var current by mutableStateOf<Featured?>(null)
        private set

    fun setFeatured(item: Featured?) {
        if (current?.key != item?.key) current = item
    }
}

/** Minimal struct for the data the backdrop needs. */
@Stable
data class Featured(
    val key: String,
    val title: String,
    val backdropUrl: String?,
    val subtitle: String? = null,
    val overview: String? = null,
)

@Composable
fun rememberFeaturedState(): FeaturedState = remember { FeaturedState() }

/**
 * Full-bleed backdrop image bound to a [FeaturedState].
 *
 * Phase 9 upgrade: richer multi-stop gradient, optional title/subtitle
 * overlay in the lower-left for the Netflix-style hero info area.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ImmersiveBackdrop(
    state: FeaturedState,
    modifier: Modifier = Modifier,
    showInfo: Boolean = true,
) {
    Box(modifier = modifier.fillMaxSize().background(StrataColors.SurfaceVoid)) {
        AnimatedContent(
            targetState = state.current,
            transitionSpec = {
                fadeIn(tween(durationMillis = 700)) togetherWith
                    fadeOut(tween(durationMillis = 700))
            },
            label = "immersive-backdrop",
        ) { featured ->
            if (featured?.backdropUrl != null) {
                AsyncImage(
                    model = featured.backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize())
            }
        }

        // Multi-stop cinematic gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.2f),
                            0.3f to Color.Black.copy(alpha = 0.35f),
                            0.6f to Color.Black.copy(alpha = 0.6f),
                            0.85f to Color.Black.copy(alpha = 0.85f),
                            1.0f to StrataColors.SurfaceVoid,
                        ),
                    ),
                ),
        )

        // Left-side gradient for text legibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.5f),
                            0.4f to Color.Black.copy(alpha = 0.15f),
                            1.0f to Color.Transparent,
                        ),
                    ),
                ),
        )

        // Optional featured info overlay
        if (showInfo) {
            val featured = state.current
            if (featured != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 32.dp, bottom = 120.dp, end = 200.dp),
                ) {
                    Text(
                        text = featured.title,
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 38.sp,
                    )
                    if (!featured.subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = featured.subtitle,
                            color = StrataColors.TextSecondary,
                            fontSize = 14.sp,
                        )
                    }
                    if (!featured.overview.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = featured.overview,
                            color = StrataColors.TextSecondary,
                            fontSize = 14.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 20.sp,
                        )
                    }
                }
            }
        }
    }
}
