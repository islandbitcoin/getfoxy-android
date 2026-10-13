package io.islandbitcoin.foxy.web

/**
 * What the page's activity hears of the page from WebHost (ENG-668): the page finished
 * loading ([loaded]), its process ended ([gone]), and reader mode went off at a card
 * session's end ([readerOff]). MainActivity passes them to its CardTapDelivery and
 * CardTapIntake (FoxyWebView.swift `pageLoaded`, `webViewWebContentProcessDidTerminate`).
 *
 * The order of a reload is here, where the JVM tests run it (PageHooksTest): [gone]
 * first, so a question still out to the dead page is forgotten, then the old page
 * dropped and the new one made.
 */
class PageHooks {
    var loaded: () -> Unit = {}
    var gone: () -> Unit = {}
    var readerOff: () -> Unit = {}

    /** The page's process ended: heard as [gone], then [destroy] and [create], in that order. */
    fun processEnded(destroy: () -> Unit, create: () -> Unit) {
        gone()
        destroy()
        create()
    }
}
