package com.example.simplebrowser.files

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Session.load(this)
        Thumbs.init(this)
        VolumeWatcher.start(this)
        com.example.simplebrowser.ServerSync.startAuto(this)
    }
}
