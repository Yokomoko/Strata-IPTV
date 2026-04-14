package com.strata.tv.ui.splash

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Strata",
                color = StrataColors.TextPrimary,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Loading your library\u2026",
                color = StrataColors.TextTertiary,
                fontSize = 14.sp,
            )

            Spacer(Modifier.height(24.dp))

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
