package com.strata.tv.ui.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.strata.tv.ui.theme.StrataColors

/**
 * Holder for the home screen's "currently-featured" item.
 *
 * Cards register themselves via [setFeatured] when they receive
 * focus.  The [ImmersiveBackdrop] composable observes [current] and
 * crossfades the screen-wide background to the new item's backdrop
 * — same UX pattern Netflix and Disney+ use on TV.
 *
 * Lives at the screen scope (not inside any rail) so a focused item
 * in *any* rail updates the same background.
 */
@Stable
class FeaturedState {
    var current by mutableStateOf<Featured?>(null)
        private set

    fun setFeatured(item: Featured?) {
        // Avoid a setState if nothing changed — keeps unrelated focus
        // events from triggering crossfade animations.
        if (current?.key != item?.key) current = item
    }
}

/** Minimal struct for the data the backdrop needs.  Avoids leaking
 *  Room entity types into pure-UI widgets. */
@Stable
data class Featured(
    val key: String,
    val title: String,
    val backdropUrl: String?,
)

@Composable
fun rememberFeaturedState(): FeaturedState = remember { FeaturedState() }

/**
 * Full-bleed backdrop image bound to a [FeaturedState].
 *
 * Sits at the back of the screen's [Box] stack; content overlays it.
 * A radial gradient at the bottom-left keeps text legible regardless
 * of how light the backdrop image is.
 */
@Composable
fun ImmersiveBackdrop(
    state: FeaturedState,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(StrataColors.SurfaceVoid)) {
        AnimatedContent(
            targetState = state.current,
            transitionSpec = {
                fadeIn(tween(durationMillis = 600)) togetherWith
                    fadeOut(tween(durationMillis = 600))
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

        // Dim + bottom-left gradient overlay — guarantees text on top
        // of the backdrop is legible.  Tuned to match Netflix's TV
        // home backdrop weighting.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.35f),
                            Color.Black.copy(alpha = 0.65f),
                            StrataColors.SurfaceVoid,
                        ),
                    ),
                ),
        )
    }
}
