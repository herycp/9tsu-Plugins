// ! Bu araç @ByAyzen tarafından | @CS-Karma için yazılmıştır.
package com.kodasusaka

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class JPFilmsPlugin: Plugin() {
    override fun load() {
        registerMainAPI(KodasusakaProvider())
    }
}