package com.strata.tv.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.strata.tv.ui.theme.StrataColors

/**
 * Persistent left rail listing every top-level destination.
 *
 * 72 dp wide — matches Sky's sidebar metrics.  Items are arranged in
 * a [Column], so D-pad up/down between them works for free via
 * Compose's directional focus traversal.
 *
 * Phase 2 Sidebar — pure structure, no real focus state plumbing yet.
 * That arrives once the screen-level FocusRequester wiring lands in
 * MainActivity.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Sidebar(
    selected: Destination,
    onSelected: (Destination) -> Unit,
    sidebarFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val itemRequesters = remember {
        Destination.entries.associateWith { FocusRequester() }
    }

    // When this composable first attaches, push focus to the
    // currently-selected item.  Callers typically request focus on
    // the rail (via [sidebarFocusRequester]) when Back is pressed
    // from the content area — that bubbles up here, and we land on
    // the right item.
    LaunchedEffect(selected) {
        runCatching { itemRequesters.getValue(selected).requestFocus() }
    }

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(72.dp)
            .focusRequester(sidebarFocusRequester),
        colors = SurfaceDefaults.colors(
            containerColor = StrataColors.SurfaceVoid,
            contentColor = StrataColors.TextPrimary,
        ),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            for (destination in Destination.entries) {
                SidebarItem(
                    destination = destination,
                    isSelected = destination == selected,
                    focusRequester = itemRequesters.getValue(destination),
                    onClick = { onSelected(destination) },
                )
            }
        }
    }
}

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

    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(width = 56.dp, height = 56.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) StrataColors.SurfaceRaised else Color.Transparent,
            focusedContainerColor = StrataColors.AccentPrimary,
            pressedContainerColor = StrataColors.AccentPrimary,
            contentColor = if (active) StrataColors.TextPrimary else StrataColors.TextTertiary,
            focusedContentColor = Color.White,
        ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(8.dp),
        ) {
            Icon(
                imageVector = if (isSelected) destination.selectedIcon else destination.icon,
                contentDescription = destination.label,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = destination.label,
                fontSize = 9.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
