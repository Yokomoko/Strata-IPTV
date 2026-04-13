package com.strata.tv.ui.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.PauseCircleFilled
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.strata.tv.ui.theme.StrataColors

/**
 * Full-screen ExoPlayer video surface backed by Media3.
 *
 * Renders via [PlayerView] inside an [AndroidView] -- this gives us
 * hardware-accelerated SurfaceView rendering with the codec pipeline
 * that Netflix, Prime and Disney+ use on Fire OS.  The Compose layer
 * handles the overlay (title bar, buffering spinner, controls) while
 * the video surface lives in its own Android View underneath.
 *
 * D-pad key handling follows the same conventions as the Flutter v1
 * player: center/select toggles play/pause, left/right seek, back
 * exits.
 */
@Composable
fun PlayerScreen(
    streamUrl: String,
    title: String,
    isLive: Boolean,
    resumePositionMs: Long,
    contentType: String,
    artworkUrl: String,
    onExit: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }

    // -- Initialise the player once -----------------------------------
    LaunchedEffect(Unit) {
        viewModel.initialize(
            streamUrl = streamUrl,
            title = title,
            isLive = isLive,
            resumePositionMs = resumePositionMs,
            contentType = contentType,
            artworkUrl = artworkUrl,
        )
    }

    // -- Wakelock -- keep screen on while playing ---------------------
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    // -- Save on exit (back-press or onExit call) ---------------------
    val exitHandler = remember(onExit) {
        {
            viewModel.saveOnExit()
            onExit()
        }
    }

    // -- Root container with D-pad key handling -----------------------
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                // Only handle key-down events (ACTION_DOWN).
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }

                when (event.nativeKeyEvent.keyCode) {
                    // Back / Escape -> exit
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_ESCAPE,
                    -> {
                        exitHandler()
                        true
                    }

                    // Play/pause toggle
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_SPACE,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    -> {
                        viewModel.togglePlayPause()
                        viewModel.showControls()
                        true
                    }

                    // Seek backward -10s
                    KeyEvent.KEYCODE_MEDIA_REWIND,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    -> {
                        viewModel.seekRelative(-10_000)
                        viewModel.showControls()
                        true
                    }

                    // Seek forward +30s
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    -> {
                        viewModel.seekRelative(30_000)
                        viewModel.showControls()
                        true
                    }

                    else -> {
                        // Any other key press shows the controls overlay.
                        viewModel.showControls()
                        false
                    }
                }
            },
    ) {
        // -- Video surface --------------------------------------------
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.player
                    useController = false // We draw our own overlay.
                    // SurfaceView is the default and preferred for hardware
                    // decode on Android TV -- no change needed.
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // -- Buffering spinner ----------------------------------------
        if (state.isBuffering) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                SpinningArc(
                    color = StrataColors.AccentPrimary,
                    modifier = Modifier.size(48.dp),
                )
            }
        }

        // -- Error overlay --------------------------------------------
        state.errorMessage?.let { errorMsg ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .padding(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(StrataColors.SurfaceRaised.copy(alpha = 0.9f))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.ErrorOutline,
                        contentDescription = "Error",
                        tint = StrataColors.StatusError,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Stream unavailable",
                        color = StrataColors.TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMsg,
                        color = StrataColors.TextSecondary,
                        fontSize = 14.sp,
                    )
                }
            }
        }

        // -- Controls overlay (auto-hide after 4s) --------------------
        AnimatedVisibility(
            visible = state.controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xCC000000),
                                Color.Transparent,
                                Color.Transparent,
                                Color(0xCC000000),
                            ),
                            startY = 0f,
                            endY = Float.POSITIVE_INFINITY,
                        ),
                    ),
            ) {
                // Top bar -- title + live badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, top = 24.dp, end = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (isLive) {
                        Spacer(Modifier.width(12.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(StrataColors.StatusLive)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "LIVE",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                // Bottom controls -- transport buttons
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isLive) {
                        Icon(
                            imageVector = Icons.Filled.Replay10,
                            contentDescription = "Rewind 10 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.width(24.dp))
                    }
                    Icon(
                        imageVector = if (state.isPlaying) {
                            Icons.Filled.PauseCircleFilled
                        } else {
                            Icons.Filled.PlayCircleFilled
                        },
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(56.dp),
                    )
                    if (!isLive) {
                        Spacer(Modifier.width(24.dp))
                        Icon(
                            imageVector = Icons.Filled.Forward30,
                            contentDescription = "Forward 30 seconds",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
        }
    }

    // -- Request focus so D-pad events reach us -----------------------
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

// -- Buffering spinner built from Canvas (no material3 dep) -----------

/**
 * A simple spinning arc indicator using only Compose Foundation APIs.
 * Avoids pulling in `androidx.compose.material3` which isn't in the
 * dependency graph for this TV-focused project.
 */
@Composable
private fun SpinningArc(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spinner")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rotation",
    )

    Canvas(modifier = modifier) {
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
