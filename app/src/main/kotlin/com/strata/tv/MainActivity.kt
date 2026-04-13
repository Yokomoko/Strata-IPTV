package com.strata.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.strata.tv.ui.theme.StrataTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host for the Compose tree.
 *
 * Phase 0 deliberately renders only a placeholder so we can verify the
 * full toolchain — Compose for TV deps, Hilt, manifest bits — actually
 * runs end-to-end on a Fire Stick. Real navigation comes in Phase 2.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StrataTheme {
                HelloStrataTv()
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HelloStrataTv() {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Strata TV",
                color = colors.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 56.sp,
            )
            Text(
                text = "Phase 0 scaffold — Compose for TV is wired up.",
                color = colors.onBackground,
                fontSize = 18.sp,
            )
        }
    }
}
