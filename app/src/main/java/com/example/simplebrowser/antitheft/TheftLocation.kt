package com.example.simplebrowser.antitheft

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** 📍 Ek baar ki location (GPS ya network, jo pehle mile). Callback `looper` ke thread par aata hai. */
object TheftLocation {

    fun hasPerm(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission", "DEPRECATION")
    fun fetch(ctx: Context, looper: Looper, timeoutMs: Long, cb: (Location?) -> Unit) {
        if (!hasPerm(ctx)) { cb(null); return }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val done = AtomicBoolean(false)
        val h = Handler(looper)
        val listeners = ArrayList<LocationListener>()

        fun lastKnown(): Location? = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Throwable) { null } }
                .maxByOrNull { it.time }
        } catch (_: Throwable) { null }

        fun finish(loc: Location?) {
            if (!done.compareAndSet(false, true)) return
            for (l in listeners) { try { lm.removeUpdates(l) } catch (_: Throwable) { } }
            h.removeCallbacksAndMessages(null)
            cb(loc ?: lastKnown())
        }

        val provs = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { p -> try { lm.isProviderEnabled(p) } catch (_: Throwable) { false } }
        if (provs.isEmpty()) { finish(null); return }

        try {
            for (p in provs) {
                val l = object : LocationListener {
                    override fun onLocationChanged(location: Location) { finish(location) }
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                listeners.add(l)
                lm.requestLocationUpdates(p, 0L, 0f, l, looper)
            }
        } catch (_: Throwable) { finish(null); return }
        h.postDelayed({ finish(null) }, timeoutMs)
    }

    fun line(loc: Location?, ctx: Context): String = when {
        loc != null -> String.format(Locale.US, "📍 https://maps.google.com/?q=%.6f,%.6f (±%dm)", loc.latitude, loc.longitude, loc.accuracy.toInt())
        !hasPerm(ctx) -> "📍 Location permission nahi di"
        else -> "📍 Location nahi mili"
    }
}
