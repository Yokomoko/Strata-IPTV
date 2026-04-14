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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.strata.tv.ui.nav.ChannelPlayInfo
import com.strata.tv.ui.theme.StrataColors

/**
 * Full-screen ExoPlayer video surface backed by Media3.
 *
 * Phase 9: polished controls overlay with VOD progress bar,
 * elapsed/remaining time labels, smooth bottom gradient,
 * and fade animation for the controls.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerScreen(
    streamUrl: String,
    title: String,
    isLive: Boolean,
    resumePositionMs: Long,
    contentType: String,
    artworkUrl: String,
    channelList: List<ChannelPlayInfo> = emptyList(),
    currentChannelIndex: Int = 0,
    seriesTitle: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    onExit: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val focusRequester = remember { FocusRequester() }

    // Track the current display title for live channel switching.
    var displayTitle by remember { mutableStateOf(title) }

    LaunchedEffect(Unit) {
        viewModel.initialize(
            streamUrl = streamUrl,
            title = title,
            isLive = isLive,
            resumePositionMs = resumePositionMs,
            contentType = contentType,
            artworkUrl = artworkUrl,
            seriesTitle = seriesTitle,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )
        if (channelList.isNotEmpty()) {
            viewModel.setChannelList(channelList, currentChannelIndex)
        }
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val exitHandler = remember(onExit) {
        {
            viewModel.saveOnExit()
            onExit()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        if (state.nextEpisode != null) {
                            viewModel.cancelAutoplay()
                        } else {
                            exitHandler()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        viewModel.togglePlayPause()
                        viewModel.showControls()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_LEFT -> {
                        viewModel.seekRelative(-10_000)
                        viewModel.showControls()
                        true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        viewModel.seekRelative(30_000)
                        viewModel.showControls()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (isLive && channelList.isNotEmpty()) {
                            val ch = viewModel.switchChannel(-1)
                            if (ch != null) displayTitle = ch.displayName
                            true
                        } else {
                            viewModel.showControls()
                            false
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (isLive && channelList.isNotEmpty()) {
                            val ch = viewModel.switchChannel(+1)
                            if (ch != null) displayTitle = ch.displayName
                            true
                        } else {
                            viewModel.showControls()
                            false
                        }
                    }
                    else -> {
                        viewModel.showControls()
                        false
                    }
                }
            },
    ) {
        // -- Video surface -----------------------------------------------
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.player
                    useController = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // -- Buffering spinner -------------------------------------------
        if (state.isBuffering) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                SpinningArc(
                    color = StrataColors.AccentPrimary,
                    modifier = Modifier.size(52.dp),
                )
            }
        }

        // -- Error overlay -----------------------------------------------
        state.errorMessage?.let { errorMsg ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .padding(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(StrataColors.SurfaceRaised.copy(alpha = 0.92f))
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

        // -- Controls overlay --------------------------------------------
        AnimatedVisibility(
            visible = state.controlsVisible,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(400)),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top gradient
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xCC000000),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )

                // Bottom gradient
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0xCC000000),
                                ),
                            ),
                        ),
                )

                // Top bar -- back + title + live badge
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
                        text = displayTitle,
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

                // Bottom controls area
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // VOD progress bar
                    if (!isLive) {
                        val player = viewModel.player
                        val positionMs = player.currentPosition.coerceAtLeast(0)
                        val durationMs = player.duration.coerceAtLeast(1)
                        val progress = if (durationMs > 0) {
                            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                        } else 0f

                        // Time labels
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = formatTime(positionMs),
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                            )
                            Text(
                                text = "-${formatTime((durationMs - positionMs).coerceAtLeast(0))}",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(Modifier.height(6.dp))

                        // Progress bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.2f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(StrataColors.AccentPrimary),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                    }

                    // Transport buttons
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isLive) {
                            Icon(
                                imageVector = Icons.Filled.Replay10,
                                contentDescription = "Rewind 10 seconds",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.width(28.dp))
                        }
                        Icon(
                            imageVector = if (state.isPlaying) {
                                Icons.Filled.PauseCircleFilled
                            } else {
                                Icons.Filled.PlayCircleFilled
                            },
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(60.dp),
                        )
                        if (!isLive) {
                            Spacer(Modifier.width(28.dp))
                            Icon(
                                imageVector = Icons.Filled.Forward30,
                                contentDescription = "Forward 30 seconds",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                }
            }
        }

        // -- Channel switch overlay (top banner) -------------------------
        if (isLive && channelList.isNotEmpty()) {
            ChannelOverlay(
                visible = state.channelOverlayVisible,
                channel = viewModel.currentChannel(),
                channelIndex = currentChannelIndex,
                channelCount = channelList.size,
            )
        }

        // -- Next episode autoplay overlay (bottom-right) ---------------
        state.nextEpisode?.let { next ->
            NextEpisodeOverlay(next = next)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

// -- Helpers -------------------------------------------------------------

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

// -- Next episode autoplay overlay ----------------------------------------

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NextEpisodeOverlay(next: NextEpisodeInfo) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(StrataColors.SurfaceRaised.copy(alpha = 0.92f))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Next Episode",
                color = StrataColors.TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = next.seriesTitle,
                color = StrataColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append("S${next.seasonNumber}E${next.episodeNumber}")
                    if (next.episodeTitle.isNotBlank()) {
                        append(" \u2013 ${next.episodeTitle}")
                    }
                },
                color = StrataColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
            )
            Spacer(Modifier.height(12.dp))
            CountdownRing(
                seconds = next.countdown,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Press BACK to cancel",
                color = StrataColors.TextTertiary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun CountdownRing(
    seconds: Int,
    modifier: Modifier = Modifier,
) {
    val sweepAngle = (seconds / 10f) * 360f
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.matchParentSize()) {
            // Background ring
            drawArc(
                color = Color.White.copy(alpha = 0.15f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
            // Countdown arc
            drawArc(
                color = StrataColors.AccentPrimary,
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        Text(
            text = "$seconds",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

// -- Buffering spinner ---------------------------------------------------

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
