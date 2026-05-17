package com.strata.tv.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.strata.tv.ui.theme.StrataColors

/**
 * Small metadata chip for genre, certification, year, etc.
 * Styled with a subtle border and dark surface background.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MetadataChip(
    text: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(StrataColors.ChipSurface)
            .border(1.dp, StrataColors.ChipBorder, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            color = StrataColors.TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Renders a comma-separated genre string as a row of chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenreChips(
    genres: String,
    modifier: Modifier = Modifier,
) {
    if (genres.isBlank()) return
    val items = genres.split(",", "|", "/").map { it.trim() }.filter { it.isNotEmpty() }
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.take(5).forEach { genre ->
            MetadataChip(text = genre)
        }
    }
}
