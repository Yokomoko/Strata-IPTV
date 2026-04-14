package com.strata.tv.ui.widgets

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.strata.tv.ui.theme.StrataColors

/**
 * A single action shown inside the [CardContextMenu].
 */
data class ContextMenuAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * Dark-themed context menu popup for TV card long-press / Menu key.
 *
 * Shows a list of focusable text-button actions in a compact dark
 * overlay. D-pad up/down navigates items, Select executes, Back
 * dismisses.  Styled to match the Strata dark streaming theme.
 *
 * Each action item is an [androidx.tv.material3.Surface] so it
 * participates properly in the Android TV / Fire Stick focus system.
 * Focus is requested on the first item when the menu appears.
 *
 * @param visible   whether the popup is currently shown
 * @param actions   list of labelled actions
 * @param onDismiss callback to close the menu (Back key or outside tap)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CardContextMenu(
    visible: Boolean,
    actions: List<ContextMenuAction>,
    onDismiss: () -> Unit,
) {
    if (!visible || actions.isEmpty()) return

    // Stable FocusRequesters keyed to the action count -- created
    // outside the loop so they survive recomposition and the
    // LaunchedEffect can reliably request focus on the first item.
    val focusRequesters = remember(actions.size) {
        List(actions.size) { FocusRequester() }
    }
    var focusedIndex by remember { mutableIntStateOf(0) }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        // Scrim -- dim the background so the popup stands out
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_BACK,
                            KeyEvent.KEYCODE_MENU -> {
                                onDismiss()
                                true
                            }
                            // Trap D-pad at the edges so focus never
                            // escapes the popup into the content behind.
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                if (focusedIndex <= 0) true   // already at top
                                else false                     // let it propagate
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (focusedIndex >= actions.lastIndex) true
                                else false
                            }
                            // Block left/right so the underlying rail
                            // doesn't scroll while the menu is open.
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT -> true
                            else -> false
                        }
                    } else false
                },
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(12.dp)

            Column(
                modifier = Modifier
                    .widthIn(min = 200.dp, max = 280.dp)
                    .shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(shape)
                    .background(StrataColors.SurfaceRaised)
                    .border(1.dp, StrataColors.SurfaceOverlay, shape)
                    .padding(vertical = 8.dp),
            ) {
                actions.forEachIndexed { index, action ->
                    var isFocused by remember { mutableStateOf(false) }

                    Surface(
                        onClick = {
                            action.onClick()
                            onDismiss()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequesters[index])
                            .onFocusChanged { state ->
                                isFocused = state.isFocused
                                if (state.isFocused) focusedIndex = index
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    when (event.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_BACK,
                                        KeyEvent.KEYCODE_MENU -> {
                                            onDismiss()
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            },
                        shape = ClickableSurfaceDefaults.shape(
                            shape = RoundedCornerShape(0.dp),
                        ),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = StrataColors.AccentPrimary.copy(alpha = 0.25f),
                            pressedContainerColor = StrataColors.AccentPrimary.copy(alpha = 0.35f),
                        ),
                    ) {
                        Text(
                            text = action.label,
                            color = if (isFocused) StrataColors.AccentPrimaryBright
                                    else StrataColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (isFocused) FontWeight.SemiBold
                                         else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }

    // Request focus on the first action once the popup is composed.
    // Using actions as a key so focus is re-requested if the menu
    // re-opens with new items.
    LaunchedEffect(actions) {
        focusRequesters.firstOrNull()?.requestFocus()
    }
}
