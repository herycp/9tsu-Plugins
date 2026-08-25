package com.myasiantv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.Vidmoly
//import com.lagradost.cloudstream3.extractors.Vidmolyto
//import com.lagradost.cloudstream3.extractors.Vidmolymbiz

@CloudstreamPlugin
class MyAsianTvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MyAsianTv())

        // Daftarkan semua varian VidMoly
        registerExtractorAPI(Vidmoly())
        //registerExtractorAPI(Vidmolyto())
        //registerExtractorAPI(Vidmolymbiz())

        // Ekstraktor lainnya
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(MixDrop())
    }
}
