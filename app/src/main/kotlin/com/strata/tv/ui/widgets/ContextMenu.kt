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
import androidx.tv.material3.ExperimentalTvMaterial3Api
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
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK
                    ) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
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
                    val focusRequester = remember { FocusRequester() }
                    var isFocused by remember { mutableStateOf(false) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { isFocused = it.isFocused }
                            .background(
                                if (isFocused) StrataColors.AccentPrimary.copy(alpha = 0.25f)
                                else Color.Transparent,
                            )
                            .onPreviewKeyEvent { event ->
                                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                    when (event.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_CENTER,
                                        KeyEvent.KEYCODE_ENTER -> {
                                            action.onClick()
                                            onDismiss()
                                            true
                                        }
                                        KeyEvent.KEYCODE_BACK,
                                        KeyEvent.KEYCODE_MENU -> {
                                            onDismiss()
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = action.label,
                            color = if (isFocused) StrataColors.AccentPrimaryBright
                                    else StrataColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }

                    // Auto-focus the first item when the menu opens
                    if (index == 0) {
                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                    }
                }
            }
        }
    }
}
