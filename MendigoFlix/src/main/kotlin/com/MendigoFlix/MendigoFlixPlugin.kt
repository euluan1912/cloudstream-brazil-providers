package com.MendigoFlix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MendigoFlixProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MendigoFlix())
    }
}
