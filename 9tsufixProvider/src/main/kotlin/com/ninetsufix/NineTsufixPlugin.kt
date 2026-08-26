package com.ninetsufix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class NineTsufixPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider 9tsu
        registerMainAPI(NineTsuFixProvider())
        
        // Mendaftarkan pengaturan untuk memilih domain
        this.openSettings = { ctx ->
            NineTsuPrefs.showDomainDialog(ctx as AppCompatActivity) {
                // Setelah menyimpan preferensi, user bisa refresh manual
            }
        }
    }
}
