package com.example.simplebrowser.files

import android.app.Activity
import android.content.Intent
import androidx.appcompat.app.AlertDialog

/** SD card / USB drive ke chhote dialogs (Home aur File Manager dono me use hote hain). */
object DriveUi {

    fun info(act: Activity, v: LocalFs.Vol) {
        AlertDialog.Builder(act)
            .setTitle(v.icon + " " + v.label)
            .setMessage(LocalFs.infoText(v))
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Android apps ko seedha unmount karne nahi deta, isliye: pehle dekho Lion ka koi transfer is drive par
     * to nahi chal raha; nahi to Settings kholte hain jahan se Eject hota hai.
     */
    fun safeEject(act: Activity, v: LocalFs.Vol) {
        if (LocalJobs.busyOn(v.root.absolutePath)) {
            AlertDialog.Builder(act)
                .setTitle("⏏ Abhi eject mat karo")
                .setMessage("Is drive par copy / move chal raha hai. Beech me nikalne se file kharab ho sakti hai.\n\nPehle transfer poora hone do, ya Transfers me jaake cancel karo.")
                .setPositiveButton("Transfers dekho") { _, _ -> act.startActivity(Intent(act, TransfersActivity::class.java)) }
                .setNegativeButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(act)
            .setTitle("⏏ Safe eject")
            .setMessage(
                "Lion ka koi transfer is drive par nahi chal raha.\n\n" +
                    "Android app ko seedha eject karne nahi deta. 'Settings kholo' dabao aur wahan " + v.label +
                    " ke aage Eject dabao. Jab Android 'safe to remove' dikhaye tabhi drive nikalo."
            )
            .setPositiveButton("Settings kholo") { _, _ -> LocalFs.openStorageSettings(act) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
