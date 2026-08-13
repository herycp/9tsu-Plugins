package com.example

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NineTsuinPlugin : Plugin() {
    override fun load(context: Context) {
        // Mendaftarkan provider 9tsu
        registerMainAPI(NineTsuinProvider())
    }
}
