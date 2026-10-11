package io.islandbitcoin.foxy.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The document-start scripts' native words: hex only for the page hash, the shim's name, every RTC name iOS removes. */
class ScriptsTest {
    @Test
    fun thePageHashIsHexOnly() {
        assertEquals("if (window.top === window) window.__foxyPageHash = 'abc123';", Scripts.pageHash("abc123"))
        assertEquals("if (window.top === window) window.__foxyPageHash = 'aae1b';", Scripts.pageHash("a'; alert(1); 'b"))
    }

    @Test
    fun theShimPostsJsonToTheNativeObjectAndLocksItself() {
        assertTrue(Scripts.shim.contains("window.${Scripts.NATIVE_OBJECT}"))
        assertTrue(Scripts.shim.contains("JSON.stringify(m)"))
        assertTrue(Scripts.shim.contains("configurable: false"))
    }

    @Test
    fun everyRtcNameIosRemovesIsRemoved() {
        for (name in listOf("RTCPeerConnection", "webkitRTCPeerConnection", "RTCDataChannel", "RTCIceCandidate", "RTCSessionDescription",
            "RTCRtpSender", "RTCRtpReceiver", "RTCRtpTransceiver", "RTCIceTransport", "RTCDtlsTransport", "RTCSctpTransport",
            "RTCCertificate", "RTCPeerConnectionIceEvent", "RTCDataChannelEvent", "RTCTrackEvent")) {
            assertTrue(name, Scripts.noWebRTC.contains("'$name'"))
        }
    }

    @Test
    fun theFlagsAreWhatThePageReads() {
        assertEquals("if (window.top === window) window.__foxyClock24 = true;", Scripts.clock24(true))
        assertTrue(Scripts.foxyDebug.contains("window.FOXY_DEBUG = true"))
        assertTrue(Scripts.showWarning.contains("window.FOXY_SHOW_WARNING = true"))
        assertTrue(Scripts.consoleSilence.contains("console.log = noop"))
        assertFalse("console.error survives", Scripts.consoleSilence.contains("console.error"))
    }
}
