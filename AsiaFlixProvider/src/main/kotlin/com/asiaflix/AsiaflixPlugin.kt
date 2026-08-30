package com.asiaflix

import android.content.Context
import com.asiaflix.extractors.PeachifyExtractor
import com.asiaflix.extractors.VideasyExtractor
import com.asiaflix.extractors.VidupExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AsiaflixPlugin : Plugin() {
    override fun load(context: Context) {
        // Daftarkan Provider Utama
        registerMainAPI(Asiaflix())
        
        // Daftarkan Ekstraktor
        registerExtractorAPI(PeachifyExtractor())
        registerExtractorAPI(VideasyExtractor())
        registerExtractorAPI(VidupExtractor())
        registerExtractorAPI(CinezoExtractor())
    }
}