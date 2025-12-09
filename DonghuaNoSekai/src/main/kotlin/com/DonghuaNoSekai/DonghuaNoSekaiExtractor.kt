package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {

    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Configura o WebViewResolver para interceptar links de stream
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.(m3u8|mp4|mkv)"""),
                useOkhttp = false,
                timeout = 15_000L
            )

            val intercepted = app.get(url, interceptor = streamResolver).url

            if (intercepted.isNotEmpty() && intercepted.contains("m3u8")) {
                val m3u8Url = intercepted
                
                val headers = mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

                // O M3u8Helper já cria os ExtractorLinks automaticamente
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8Url,
                    referer = mainUrl,
                    headers = headers
                ).forEach(callback)

                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}