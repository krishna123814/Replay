package com.example.simplebrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.widget.Toast

/** Upload ke liye file kahan se chuni jaye: Lion File Manager (AIC / HF / Phone) ya phone ka system picker. */
object LionPicker {
    fun hasLogin(ctx: Context): Boolean {
        val a = ctx.applicationContext
        return DlSettings.getStr(a, ServerSync.K_USER, "").isNotEmpty() && DlSettings.getStr(a, ServerSync.K_PASS, "").isNotEmpty()
    }

    fun filesIntent(ctx: Context, picker: Intent): Intent =
        Intent(ctx, com.example.simplebrowser.files.HomeActivity::class.java)
            .putExtra("pick", true)
            .putExtra("pick_multi", picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))

    /** Login nahi hai to seedha system picker; warna 2 option ka dialog. */
    fun ask(act: Activity, onFiles: () -> Unit, onSystem: () -> Unit, onCancel: () -> Unit) {
        if (!hasLogin(act)) {
            onSystem()
            return
        }
        AlertDialog.Builder(act)
            .setTitle("File kahan se chunni hai?")
            .setItems(arrayOf("📁 Lion File Manager (AIC / HF / Phone)", "📱 Phone ka system picker")) { _, i ->
                if (i == 0) onFiles() else onSystem()
            }
            .setOnCancelListener { onCancel() }
            .show()
    }
}

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

        LionPicker.ask(
            this,
            onFiles = { launchPicker(LionPicker.filesIntent(this, picker)) },
            onSystem = { launchPicker(Intent.createChooser(picker, "File chuniye")) },
            onCancel = { sendResultAndFinish(null) }
        )
    }

    private fun launchPicker(i: Intent) {
        try {
            startActivityForResult(i, requestCode)
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
