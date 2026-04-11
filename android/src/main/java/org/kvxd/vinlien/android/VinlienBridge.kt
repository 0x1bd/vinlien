package org.kvxd.vinlien.android

import android.webkit.JavascriptInterface

class VinlienBridge(private val service: MusicService) {

    @JavascriptInterface
    fun updateMedia(title: String, artist: String, album: String, artworkUrl: String) {
        service.updateMetadata(title, artist, artworkUrl)
    }

    @JavascriptInterface
    fun updatePosition(position: Double, duration: Double) {
        service.updatePosition(position, duration)
    }

    @JavascriptInterface
    fun updatePlayState(playing: Boolean) {
        service.updatePlayState(playing)
    }
}
