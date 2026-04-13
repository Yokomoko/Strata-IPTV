package com.strata.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.strata.tv.ui.theme.StrataColors
import com.strata.tv.ui.widgets.Rail

/**
 * Home screen — Netflix-style vertical stack of horizontal rails.
 *
 * Phase 2 renders the layout with hard-coded sample rails so the
 * focus model + scrolling can be tested without depending on the
 * sync service running first.  The real rails arrive in a later
 * phase that wires this to ViewModels backed by Phase 1 repos.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(StrataColors.SurfaceVoid)
            .verticalScroll(rememberScrollState()),
    ) {
        Hero()
        Spacer(Modifier.height(8.dp))

        Rail(
            title = "Continue Watching",
            accentColor = StrataColors.AccentSecondary,
            items = sampleCw,
        ) { index, item -> SampleCard(item) }

        Rail(
            title = "Latest from Netflix",
            accentColor = NetflixRed,
            items = sampleNetflix,
        ) { index, item -> SampleCard(item) }

        Rail(
            title = "Latest from Disney+",
            accentColor = DisneyBlue,
            items = sampleDisney,
        ) { index, item -> SampleCard(item) }

        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Hero() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1E1040),
                        Color(0xFF150D30),
                        StrataColors.SurfaceVoid,
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 32.dp, vertical = 24.dp),
        ) {
            Text(
                text = "Strata",
                color = StrataColors.AccentPrimary,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Stat("0", "Channels", StrataColors.StatusLive)
                Stat("0", "Movies", StrataColors.AccentPrimary)
                Stat("0", "Box Sets", StrataColors.AccentSecondary)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Stat(value: String, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = value,
            color = color,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            color = StrataColors.TextTertiary,
            fontSize = 11.sp,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SampleCard(label: String) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = { /* Phase 2 placeholder */ },
        modifier = Modifier
            .size(width = 120.dp, height = 220.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = StrataColors.SurfaceRaised,
            focusedContainerColor = StrataColors.AccentPrimary,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (focused) Color.White else StrataColors.TextPrimary,
                fontSize = 11.sp,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

// Sample data so the home screen has something to show before
// repositories are wired in.  These get replaced in the next phase.
private val sampleCw = listOf(
    "Continue 1", "Continue 2", "Continue 3", "Continue 4", "Continue 5",
)
private val sampleNetflix = (1..15).map { "Netflix $it" }
private val sampleDisney = (1..15).map { "Disney $it" }

private val NetflixRed = Color(0xFFE50914)
private val DisneyBlue = Color(0xFF0063E5)
