package io.islandbitcoin.foxy.web

import androidx.webkit.WebViewFeature
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walls the page will not load without (docs/ROUTE.md §4): the dead-end
 * proxy is one of them, so a WebView without PROXY_OVERRIDE gets no page rather
 * than a page behind fewer walls.
 */
class WebHostTest {
    @Test
    fun thePageIsNotLoadedWithoutTheDeadEndProxy() {
        assertTrue(WebViewFeature.PROXY_OVERRIDE in WebHost.REQUIRED_FEATURES)
        assertTrue(WebViewFeature.WEB_MESSAGE_LISTENER in WebHost.REQUIRED_FEATURES)
        assertTrue(WebViewFeature.DOCUMENT_START_SCRIPT in WebHost.REQUIRED_FEATURES)
    }
}
