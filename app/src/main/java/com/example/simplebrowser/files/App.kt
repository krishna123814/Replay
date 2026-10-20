package com.example.simplebrowser.files

import android.app.Application
import android.util.Log
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Crash ka poora error file me save karo; agli baar Lion kholne par screen par dikhega
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "last_crash.txt").writeText(
                    "Thread: ${t.name}\n" + Log.getStackTraceString(e)
                )
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
        Session.load(this)
        Thumbs.init(this)
        VolumeWatcher.start(this)
        com.example.simplebrowser.ServerSync.startAuto(this)
    }
}
