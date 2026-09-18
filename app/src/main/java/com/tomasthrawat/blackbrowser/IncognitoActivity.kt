package com.tomasthrawat.blackbrowser

import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/**
 * Private browser profile. It runs in a separate Android process so WebView cookies,
 * local storage and cache are not shared with the normal browser process.
 */
class IncognitoActivity : MainActivity() {
    companion object {
        private var webViewProfileConfigured = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !webViewProfileConfigured) {
            WebView.setDataDirectorySuffix("incognito")
            webViewProfileConfigured = true
        }
        intent.putExtra(
            "com.tomasthrawat.blackbrowser.EXTRA_INCOGNITO_MODE",
            true
        )
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        // Clear the isolated WebView cache so private browsing data does not remain
        // in the incognito profile after the activity is closed.
        runCatching { WebView(this).apply { clearCache(true); destroy() } }
        super.onDestroy()
    }
}
