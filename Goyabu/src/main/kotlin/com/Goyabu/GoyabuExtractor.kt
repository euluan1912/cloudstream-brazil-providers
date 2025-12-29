package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import java.net.URI

object GoyabuExtractor {
    private val itagQualityMap = mapOf(
        18 to 360,   22 to 720,   37 to 1080,  59 to 480,
        133 to 240,  134 to 360,  135 to 480,  136 to 720,
        137 to 1080, 160 to 144,  242 to 240,  243 to 360,
        244 to 480,  247 to 720,  248 to 1080
    )
    
    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val pageResponse = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "https://goyabu.io/",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                ),
                timeout = 30
            )
            
            val html = pageResponse.text
            val doc = Jsoup.parse(html)
            
            if (extractJwPlayerUrls(doc, url, name, callback)) {
                return true
            }
            
            if (extractM3U8Urls(doc, url, name, callback)) {
                return true
            }
            
            if (extractBloggerUrls(doc, url, name, callback)) {
                return true
            }
            
            false
            
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractJwPlayerUrls(
        doc: org.jsoup.nodes.Document,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val jwVideoElement = doc.selectFirst(".jw-video.jw-reset[src]")
        jwVideoElement?.attr("src")?.let { videoUrl ->
            if (isDirectVideoUrl(videoUrl)) {
                if (processDirectVideoUrl(videoUrl, referer, name, callback)) {
                    found = true
                }
            }
        }

        if (!found) {
            doc.select("video[src*='googlevideo.com']").forEach { element ->
                val src = element.attr("src")
                if (src.isNotBlank() && isDirectVideoUrl(src)) {
                    if (processDirectVideoUrl(src, referer, name, callback)) {
                        found = true
                    }
                }
            }
        }

        if (!found) {
            val scripts = doc.select("script")
            val directVideoPattern = Regex("""https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*videoplayback[^"'\s]*""")
            
            scripts.forEach { script ->
                val matches = directVideoPattern.findAll(script.html())
                matches.forEach { match ->
                    val url = match.value
                    if (isDirectVideoUrl(url) && !url.contains("m3u8")) {
                        if (processDirectVideoUrl(url, referer, name, callback)) {
                            found = true
                        }
                    }
                }
            }
        }

        return found
    }
    
    private fun isDirectVideoUrl(url: String): Boolean {
        return (url.contains("googlevideo.com") && 
                url.contains("videoplayback") && 
                !url.contains("m3u8"))
    }
    
    private suspend fun processDirectVideoUrl(
        videoUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val itag = extractItagFromUrl(videoUrl)
            val quality = itagQualityMap[itag] ?: 720 
            val qualityLabel = getQualityLabel(quality)

            val extractorLink = newExtractorLink(
                source = "Goyabu JWPlayer",
                name = "$name ($qualityLabel) [MP4]",
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = quality
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Origin" to (URI(referer).host?.let { "https://$it" } ?: "https://goyabu.io")
                )
            }

            callback(extractorLink)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun extractM3U8Urls(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val scripts = doc.select("script")
        var found = false
        
        scripts.forEach { script ->
            val scriptContent = script.html()
            
            val patterns = listOf(
                """api\.anivideo\.net/videohls\.php[^"'\s]*""".toRegex(),
                """https?://[^"'\s]*\.m3u8[^"'\s]*""".toRegex(),
                """"(?:file|video|src|url)"\s*:\s*"([^"']*api\.anivideo[^"']+)"""".toRegex()
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(scriptContent)
                matches.forEach { match ->
                    var videoUrl = match.value
                    
                    if (pattern.pattern.contains("""["']\s*:\s*["']""")) {
                        val group = match.groupValues.getOrNull(1)
                        if (group != null) videoUrl = group
                    }
                    
                    if (isM3U8Url(videoUrl)) {
                        if (processM3U8Url(videoUrl, originalUrl, name, callback)) {
                            found = true
                        }
                    }
                }
            }
        }
        
        val elements = doc.select("""
            [src*=".m3u8"],
            [data-src*=".m3u8"],
            [href*=".m3u8"]
        """.trimIndent())
        
        elements.forEach { element ->
            val m3u8Url = element.attr("src") 
                ?: element.attr("data-src") 
                ?: element.attr("href")
            
            if (m3u8Url.isNotBlank() && isM3U8Url(m3u8Url)) {
                if (processM3U8Url(m3u8Url, originalUrl, name, callback)) {
                    found = true
                }
            }
        }
        
        return found
    }
    
    private suspend fun processM3U8Url(
        m3u8Url: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            var url = cleanUrl(m3u8Url)
            
            if (url.contains("api.anivideo.net/videohls.php")) {
                val dParamRegex = """[?&]d=([^&]+)""".toRegex()
                val match = dParamRegex.find(url)
                
                match?.let {
                    val encodedUrl = it.groupValues[1]
                    try {
                        url = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
                    } catch (e: Exception) {
                        // Ignorar erro
                    }
                }
            }
            
            if (!url.contains(".m3u8") && !url.contains("m3u8")) {
                return false
            }
            
            val quality = determineM3U8Quality(url)
            val qualityLabel = getQualityLabel(quality)
            
            val extractorLink = newExtractorLink(
                source = "Goyabu",
                name = "$name ($qualityLabel) [HLS]",
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = quality
                this.headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            }
            
            callback(extractorLink)
            return true
            
        } catch (e: Exception) {
            return false
        }
    }
    
    private suspend fun extractBloggerUrls(
        doc: org.jsoup.nodes.Document,
        originalUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        
        val iframes = doc.select("iframe[src*='blogger.com'], iframe[src*='video.g']")
        iframes.forEach { iframe ->
            val src = iframe.attr("src").trim()
            if (src.isNotBlank() && (src.contains("blogger.com") || src.contains("video.g"))) {
                if (processBloggerIframe(src, originalUrl, name, callback)) {
                    found = true
                }
            }
        }
        
        val scripts = doc.select("script")
        scripts.forEach { script ->
            val scriptContent = script.html()
            
            val patterns = listOf(
                """https?://[^"'\s]*blogger\.com/video\.g\?[^"'\s]*""".toRegex(),
                """video\.g\?[^"'\s]*token=[^"'\s]*""".toRegex(),
                """https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*videoplayback[^"'\s]*""".toRegex(),
                """https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*itag=\d+[^"'\s]*""".toRegex()
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(scriptContent)
                matches.forEach { match ->
                    var videoUrl = match.value
                    
                    if (videoUrl.startsWith("//")) {
                        videoUrl = "https:$videoUrl"
                    } else if (videoUrl.startsWith("/") || videoUrl.startsWith("./")) {
                        videoUrl = "https://www.blogger.com$videoUrl"
                    } else if (videoUrl.startsWith("video.g")) {
                        videoUrl = "https://www.blogger.com/$videoUrl"
                    }
                    
                    if (isBloggerUrl(videoUrl)) {
                        if (processBloggerVideoUrl(videoUrl, originalUrl, name, callback)) {
                            found = true
                        }
                    }
                }
            }
        }
        
        return found
    }
    
    private suspend fun processBloggerIframe(
        iframeUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(
                iframeUrl,
                headers = mapOf(
                    "Referer" to referer,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            )
            
            val html = response.text
            val videoPattern = """https?://[^"'\s]*\.googlevideo\.com/[^"'\s]*""".toRegex()
            val matches = videoPattern.findAll(html)
            
            var found = false
            matches.forEach { match ->
                val videoUrl = match.value
                if (isBloggerUrl(videoUrl)) {
                    val itag = extractItagFromUrl(videoUrl)
                    val quality = itagQualityMap[itag] ?: 360
                    val qualityLabel = getQualityLabel(quality)
                    
                    val extractorLink = newExtractorLink(
                        source = "Goyabu Blogger",
                        name = "$name ($qualityLabel)",
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = iframeUrl
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to iframeUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                            "Origin" to "https://www.blogger.com"
                        )
                    }
                    
                    callback(extractorLink)
                    found = true
                }
            }
            
            found
            
        } catch (e: Exception) {
            false
        }
    }
    
    private suspend fun processBloggerVideoUrl(
        videoUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            var url = videoUrl
            
            if (url.contains("blogger.com/video.g")) {
                return processBloggerIframe(url, referer, name, callback)
            }
            
            if (url.contains("googlevideo.com")) {
                val itag = extractItagFromUrl(url)
                val quality = itagQualityMap[itag] ?: 360
                val qualityLabel = getQualityLabel(quality)
                
                val extractorLink = newExtractorLink(
                    source = "Goyabu Blogger",
                    name = "$name ($qualityLabel)",
                    url = url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to referer,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Origin" to "https://www.blogger.com"
                    )
                }
                
                callback(extractorLink)
                return true
            }
            
            false
            
        } catch (e: Exception) {
            false
        }
    }
    
    private fun cleanUrl(url: String): String {
        var cleaned = url.trim()
        
        if (cleaned.startsWith("\"url\":\"")) {
            cleaned = cleaned.removePrefix("\"url\":\"")
        }
        if (cleaned.startsWith("\"")) {
            cleaned = cleaned.removePrefix("\"")
        }
        if (cleaned.endsWith("\"")) {
            cleaned = cleaned.removeSuffix("\"")
        }
        
        cleaned = cleaned.replace("\\/", "/")
        
        return cleaned
    }
    
    private fun determineM3U8Quality(url: String): Int {
        val urlLower = url.lowercase()
        
        return when {
            urlLower.contains("1080") || urlLower.contains("fhd") -> 1080
            urlLower.contains("720") || urlLower.contains("hd") -> 720
            urlLower.contains("480") -> 480
            urlLower.contains("360") -> 360
            else -> 720
        }
    }
    
    private fun getQualityLabel(quality: Int): String {
        return when {
            quality >= 2160 -> "4K"
            quality >= 1440 -> "QHD"
            quality >= 1080 -> "FHD"
            quality >= 720 -> "HD"
            quality >= 480 -> "SD"
            else -> "SD"
        }
    }
    
    private fun extractItagFromUrl(url: String): Int {
        val itagPattern = """[?&]itag=(\d+)""".toRegex()
        val match = itagPattern.find(url)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 18
    }
    
    private fun isM3U8Url(url: String): Boolean {
        return url.contains("m3u8") ||
               url.contains(".m3u8") ||
               url.contains("anivideo.net")
    }
    
    private fun isBloggerUrl(url: String): Boolean {
        return url.contains("googlevideo.com") ||
               url.contains("blogger.com") ||
               url.contains("video.g") ||
               url.contains("videoplayback")
    }
}
