package com.drmclmy

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.extractors.Dwish
import com.lagradost.cloudstream3.extractors.StreamTape

@CloudstreamPlugin
class DramacoolPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Dramacool())
        
        // Extractor bawaan
        registerExtractorAPI(Dwish())
        registerExtractorAPI(dlions())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(MixDropSi())
        
        // Extractor kustom untuk VidBasic
        //registerExtractorAPI(VidBasicExtractor())
    }
}
