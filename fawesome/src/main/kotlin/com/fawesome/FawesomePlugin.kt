// FawesomePlugin.kt
package com.fawesometv

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FawesomePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FawesomeTvProvider())

        // Menambahkan pengaturan untuk memilih halaman depan
        this.openSettings = { ctx ->
            FawesomePrefs.showMainPageDialog(ctx as AppCompatActivity) {
                // Setelah menyimpan preferensi, user bisa refresh manual
            }
        }
    }
}