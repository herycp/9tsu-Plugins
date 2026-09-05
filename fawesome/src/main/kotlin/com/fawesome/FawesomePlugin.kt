package com.fawesome

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FawesomePlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider Fawesome
        registerMainAPI(FawesomeProvider())
        
        // Memanggil showLayoutDialog sebagai pengganti showDomainDialog
        this.openSettings = { ctx ->
            FawesomePrefs.showLayoutDialog(ctx) {
                // Tindakan opsional setelah pengaturan disimpan (misalnya reload)
            }
        }
    }
}
