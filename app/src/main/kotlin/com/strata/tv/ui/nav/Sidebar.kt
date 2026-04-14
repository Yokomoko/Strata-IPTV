package com.strata.tv.ui.nav

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.strata.tv.ui.theme.StrataColors

/**
 * Premium streaming-app sidebar — icon-only rail with a subtle active
 * indicator pill, brand dot at the top, and an enrichment progress
 * ring at the bottom when TMDB metadata is being fetched.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Sidebar(
    selected: Destination,
    onSelected: (Destination) -> Unit,
    sidebarFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    enrichmentProgress: Float = 0f,
    enrichmentRunning: Boolean = false,
) {
    val itemRequesters = remember {
        Destination.entries.associateWith { FocusRequester() }
    }

    LaunchedEffect(selected) {
        runCatching { itemRequesters.getValue(selected).requestFocus() }
    }

    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxHeight()
            .width(56.dp)
            .focusRequester(sidebarFocusRequester)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFF0E0E16),
                        0.4f to StrataColors.SurfaceBase,
                        1.0f to Color(0xFF0E0E16),
                    ),
                ),
            )
            .padding(vertical = 12.dp),
    ) {
        // Brand dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(StrataColors.AccentPrimary),
        )
        Spacer(Modifier.height(16.dp))

        for (destination in Destination.entries) {
            SidebarItem(
                destination = destination,
                isSelected = destination == selected,
                focusRequester = itemRequesters.getValue(destination),
                onClick = { onSelected(destination) },
            )
            Spacer(Modifier.height(4.dp))
        }

        // Enrichment progress ring at the bottom
        if (enrichmentRunning) {
            Spacer(Modifier.height(12.dp))
            EnrichmentRing(progress = enrichmentProgress)
        }
    }
}

// -------------------------------------------------------------------------
// Sidebar item
// -------------------------------------------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SidebarItem(
    destination: Destination,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val active = isSelected || isFocused

    val shape = RoundedCornerShape(14.dp)

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .then(
                if (isFocused) Modifier.border(
                    width = 2.dp,
                    color = StrataColors.AccentPrimary,
                    shape = shape,
                ) else Modifier,
            ),
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                isSelected -> StrataColors.AccentPrimary.copy(alpha = 0.15f)
                else -> Color.Transparent
            },
            focusedContainerColor = StrataColors.AccentPrimary.copy(alpha = 0.25f),
            pressedContainerColor = StrataColors.AccentPrimary.copy(alpha = 0.3f),
            contentColor = if (active) Color.White else StrataColors.TextTertiary,
            focusedContentColor = Color.White,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (active) destination.selectedIcon else destination.icon,
                contentDescription = destination.label,
                modifier = Modifier.size(20.dp),
            )
        }
    }

    // Active indicator pill
    if (isSelected) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(width = 16.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(StrataColors.AccentPrimary),
        )
    }
}

// -------------------------------------------------------------------------
// Enrichment progress ring — circular arc + percentage
// -------------------------------------------------------------------------

@Composable
private fun EnrichmentRing(progress: Float) {
    val percent = (progress * 100).toInt().coerceIn(0, 100)
    val sweepAngle = progress * 360f
    val trackColor = StrataColors.SurfaceRaised
    val ringColor = StrataColors.AccentSecondary

    Box(
        modifier = Modifier.size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(36.dp)) {
            val strokeWidth = 3.dp.toPx()
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

            // Background track
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            // Progress arc
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        Text(
            text = "$percent",
            color = StrataColors.TextSecondary,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
