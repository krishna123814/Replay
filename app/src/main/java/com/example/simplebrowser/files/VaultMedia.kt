package com.example.simplebrowser.files

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.example.simplebrowser.vault.CryptoVault
import java.io.IOException

/** Abhi khula (unlocked) vault, jisse player seedha stream karta hai. Lock hote hi null. */
object VaultSession {
    @Volatile var vault: CryptoVault? = null

    /** "vault://v/<parentDirId ya _>/<file naam>" */
    fun url(parentId: String, name: String): String =
        Uri.Builder().scheme("vault").authority("v")
            .appendPath(if (parentId.isEmpty()) "_" else parentId)
            .appendPath(name).build().toString()
}

/** Media3 DataSource: vault ki file ko seek karke padhta hai, sirf zaroori chunk decrypt hota hai. */
class VaultDataSource : BaseDataSource(false) {
    private var reader: CryptoVault.Reader? = null
    private var curUri: Uri? = null
    private var pos = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        curUri = dataSpec.uri
        transferInitializing(dataSpec)
        val v = VaultSession.vault ?: throw IOException("Vault lock ho gaya hai")
        val seg = dataSpec.uri.pathSegments
        if (seg.size < 2) throw IOException("Vault URL galat hai")
        val r = v.openReader(if (seg[0] == "_") "" else seg[0], seg[1])
        reader = r
        if (dataSpec.position > r.length()) throw IOException("Seek position file se bahar hai")
        pos = dataSpec.position
        val left = r.length() - pos
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) left else minOf(dataSpec.length, left)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: throw IOException("Vault reader band hai")
        val n = r.read(pos, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = curUri

    override fun close() {
        try {
            reader?.close()
        } finally {
            reader = null
            curUri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

/** "vault" scheme ko VaultDataSource par bhejta hai (disk cache se pehle, taaki decrypted data cache me na jaye); baaki sab upstream par. */
class VaultRoutingFactory(private val upstream: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = Router(upstream.createDataSource())

    private class Router(private val net: DataSource) : DataSource {
        private var cur: DataSource? = null
        private val listeners = ArrayList<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners.add(transferListener)
            net.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val ds: DataSource = if (dataSpec.uri.scheme == "vault") {
                val v = VaultDataSource()
                for (l in listeners) v.addTransferListener(l)
                v
            } else {
                net
            }
            cur = ds
            return ds.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            (cur ?: throw IOException("DataSource khula nahi")).read(buffer, offset, length)

        override fun getUri(): Uri? = cur?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = cur?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                cur?.close()
            } finally {
                cur = null
            }
        }
    }
}
