// FawesomePlugin.kt
package com.fawesome

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@CloudstreamPlugin
class FawesomePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FawesomeTvProvider())

        // Menambahkan pengaturan untuk memilih halaman depan
        this.openSettings = { ctx ->
            CoroutineScope(Dispatchers.Main).launch {
                FawesomePrefs.showMainPageDialog(ctx as AppCompatActivity) {
                    // Setelah menyimpan preferensi, user bisa refresh manual
                }
            }
        }
    }
}