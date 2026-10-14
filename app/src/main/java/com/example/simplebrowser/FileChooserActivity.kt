package com.example.simplebrowser

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.widget.Toast

// Transparent activity: FloatingService (ek Service) khud file picker nahi khol sakti,
// isliye ye picker kholti hai aur result FloatingService.filePathCallback se WebView ko deti hai.
@Suppress("DEPRECATION")
class FileChooserActivity : Activity() {

    private val requestCode = 5173

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val picker: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        }

        if (picker == null) {
            sendResultAndFinish(null)
            return
        }

        try {
            startActivityForResult(Intent.createChooser(picker, "File chuniye"), requestCode)
        } catch (e: Exception) {
            Toast.makeText(this, "File picker nahi khul paya: " + e.message, Toast.LENGTH_LONG).show()
            sendResultAndFinish(null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val results = if (requestCode == this.requestCode) {
            WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        } else null
        sendResultAndFinish(results)
    }

    private fun sendResultAndFinish(results: Array<android.net.Uri>?) {
        FloatingService.filePathCallback?.onReceiveValue(results)
        FloatingService.filePathCallback = null
        finish()
    }

    override fun onDestroy() {
        // Agar bina result ke band ho gayi to WebView ko null de do (warna upload atak jata hai)
        FloatingService.filePathCallback?.onReceiveValue(null)
        FloatingService.filePathCallback = null
        super.onDestroy()
    }
}
