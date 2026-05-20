package com.strata.tv.ui.splash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.strata.tv.R
import com.strata.tv.ui.theme.StrataColors

/**
 * Full-screen branded splash overlay shown while the initial sync
 * populates the library. Auto-dismissed by the caller once data
 * arrives; this composable only handles the visual layout.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SplashOverlay(
    enrichmentFraction: Float = 0f,
    enrichmentRunning: Boolean = false,
) {
    // Play the Strata startup sting once when the splash first appears.
    LaunchedEffect(Unit) { StartupSound.play() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Full-bleed Strata brand background (same SVG used on the
        // first-run sync screen).  The wordmark + TV icon are baked
        // into the image, so the foreground drops the tiny banner
        // and just shows the "Loading\u2026" status + progress arc.
        Image(
            painter = painterResource(R.drawable.splash_bg),
            contentDescription = "Strata",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        // Status text + progress arc sit hard at the bottom of the
        // screen so they don't fight the centered logo in the
        // background.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            Text(
                text = "Loading your library\u2026",
                color = StrataColors.TextSecondary,
                fontSize = 14.sp,
            )

            Spacer(Modifier.height(20.dp))

            if (enrichmentRunning && enrichmentFraction > 0f) {
                // Determinate arc showing enrichment progress.
                Canvas(modifier = Modifier.size(32.dp)) {
                    val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    // Track.
                    drawArc(
                        color = StrataColors.SurfaceOverlay,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = stroke,
                    )
                    // Progress.
                    drawArc(
                        color = StrataColors.AccentPrimary,
                        startAngle = -90f,
                        sweepAngle = 360f * enrichmentFraction,
                        useCenter = false,
                        style = stroke,
                    )
                }
            } else {
                // Indeterminate spinning arc.
                SpinningArc()
            }
        }
    }
}

@Composable
private fun SpinningArc() {
    val transition = rememberInfiniteTransition(label = "splash-spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
        ),
        label = "splash-spin-angle",
    )

    Canvas(modifier = Modifier.size(32.dp)) {
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = StrataColors.AccentPrimary,
            startAngle = angle - 90f,
            sweepAngle = 90f,
            useCenter = false,
            style = stroke,
        )
    }
}
