package com.strata.tv.ui.widgets

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Full-screen WebView overlay that plays a YouTube trailer via the embed URL.
 *
 * - JavaScript is enabled (required by YouTube embed).
 * - A desktop user-agent is set so YouTube serves the embed player on Fire Stick.
 * - Back button dismisses the overlay and destroys the WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TrailerWebView(
    youtubeKey: String,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }

    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(android.graphics.Color.BLACK)

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = {
                webView.apply {
                    // youtube-nocookie.com is YouTube's privacy-enhanced
                    // embed domain — it bypasses some of the embed
                    // restrictions that produce "Video unavailable" /
                    // configuration errors on www.youtube.com/embed,
                    // because it doesn't apply the same set of cookie /
                    // referrer policies.
                    //
                    // Query params:
                    //  - autoplay=1: start immediately, this is a TV.
                    //  - playsinline=1: don't try to launch a fullscreen
                    //    handler that doesn't exist on Fire Stick WebView.
                    //  - rel=0: don't show "related videos" at the end —
                    //    those are not navigable with a D-pad.
                    //  - modestbranding=1: drop the big YT watermark.
                    //  - controls=0: same reason as rel — non-touch.
                    //  - iv_load_policy=3: no annotations / cards.
                    //  - fs=0: no fullscreen button (we're already
                    //    full-screen via the overlay).
                    val url = "https://www.youtube-nocookie.com/embed/$youtubeKey" +
                        "?autoplay=1&playsinline=1&rel=0&modestbranding=1" +
                        "&controls=0&iv_load_policy=3&fs=0"
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
