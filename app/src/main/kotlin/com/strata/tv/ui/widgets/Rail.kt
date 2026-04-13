package com.strata.tv.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.foundation.lazy.list.TvLazyRow
import androidx.tv.foundation.lazy.list.itemsIndexed
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.strata.tv.ui.theme.StrataColors

/**
 * A horizontal rail of items — the home screen's primary unit.
 *
 * Uses [TvLazyRow] (TV-flavoured LazyRow) so that:
 * - Items off-screen are not built, keeping memory bounded for long
 *   rails (a "Latest from Netflix" rail can have 40+ posters).
 * - D-pad Right/Left navigates between items for free.
 * - Auto-scroll follows focus — Compose for TV's lazy lists handle
 *   the bring-into-view behaviour the Flutter app had to reimplement
 *   manually.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun <T> Rail(
    title: String,
    accentColor: Color,
    items: List<T>,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp),
    itemSpacing: Int = 12,
    item: @Composable (Int, T) -> Unit,
) {
    if (items.isEmpty()) return

    Column {
        Row(
            modifier = Modifier.padding(start = 32.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 4.dp, height = 20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                color = StrataColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        TvLazyRow(
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(itemSpacing.dp),
        ) {
            itemsIndexed(items) { index, value ->
                item(index, value)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
