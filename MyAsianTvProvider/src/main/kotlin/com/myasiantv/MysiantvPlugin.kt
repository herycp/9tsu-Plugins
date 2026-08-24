package com.myasiantv

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.Vidmoly

@CloudstreamPlugin
class MyAsianTvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MyAsianTv())
        // Extractor bawaan CloudStream
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(MixDrop())
        registerExtractorAPI(Vidmoly()) // Sudah ada di CloudStream
    }
}