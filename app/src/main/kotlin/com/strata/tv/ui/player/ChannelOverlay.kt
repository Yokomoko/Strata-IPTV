package com.strata.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.strata.tv.ui.live.ChannelLogo
import com.strata.tv.ui.nav.ChannelPlayInfo
import com.strata.tv.ui.theme.StrataColors

/**
 * Mini channel info banner shown at the top of the player when
 * the user switches channels with D-pad Up/Down.
 *
 * Displays channel logo, name, channel number within the list,
 * and the current/next programme titles.  Auto-hides after 4 seconds
 * (controlled by [PlayerViewModel]).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChannelOverlay(
    visible: Boolean,
    channel: ChannelPlayInfo?,
    channelIndex: Int,
    channelCount: Int,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && channel != null,
        enter = fadeIn(tween(200)) + slideInVertically(
            animationSpec = tween(250),
            initialOffsetY = { -it },
        ),
        exit = fadeOut(tween(350)) + slideOutVertically(
            animationSpec = tween(300),
            targetOffsetY = { -it },
        ),
        modifier = modifier,
    ) {
        channel?.let { ch ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xDD000000),
                                Color(0xAA000000),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 40.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Channel logo
                    ChannelLogo(
                        logoUrl = ch.logoUrl,
                        displayName = ch.displayName,
                    )

                    Spacer(Modifier.width(16.dp))

                    // Channel info
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // Channel number badge + name
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Channel position in list
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(StrataColors.AccentPrimary.copy(alpha = 0.85f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "${channelIndex + 1}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = ch.displayName,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(Modifier.height(6.dp))

                        // Now/Next programme info
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (ch.nowTitle != null) {
                                Text(
                                    text = "NOW",
                                    color = StrataColors.StatusLive,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = ch.nowTitle,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                            if (ch.nextTitle != null) {
                                if (ch.nowTitle != null) {
                                    Spacer(Modifier.width(16.dp))
                                    // Thin divider
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(14.dp)
                                            .background(Color.White.copy(alpha = 0.3f)),
                                    )
                                    Spacer(Modifier.width(16.dp))
                                }
                                Text(
                                    text = "NEXT",
                                    color = StrataColors.AccentSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = ch.nextTitle,
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (ch.nowTitle == null && ch.nextTitle == null) {
                                Text(
                                    text = "No guide data",
                                    color = StrataColors.TextTertiary,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }

                    // Channel count indicator
                    Spacer(Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(StrataColors.SurfaceFloat.copy(alpha = 0.7f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "${channelIndex + 1} / $channelCount",
                            color = StrataColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
