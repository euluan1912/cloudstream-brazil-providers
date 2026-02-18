package com.MendigoFlix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

object MendigoFlixExtractor {
    private const val API_COOKIE = "SITE_TOTAL_ID=aTYqe6GU65PNmeCXpelwJwAAAMi; __dtsu=104017651574995957BEB724C6373F9E; __cc_id=a44d1e52993b9c2Oaaf40eba24989a06; __cc_cc=ACZ4nGNQSDQXsTFMNTWyTDROskw2MkhMTDDMXSE1KNDKxtLBMNDBjAIJMC4fgVe%2B%2F%2BDngAHemT8XsDBKrv%2FF3%2F2F2%2F%2FF0ZGhFP15u.VnW-1Y0o8o6/84-1.2.1.1-4_OXh2hYevsbO8hINijDKB8O_SPowh.pNojloHEbwX_qZorbmW8u8zqV9B7UsV6bbRmCWx_dD17mA7vJJklpOD9WBh9DA0wMV2a1QSKuR2J3FN9.TRzOUM4AhnTGFd8dJH8bHfqQdY7uYuUg7Ny1TVQDF9kXqyEPtnmkZ9rFkqQ2KS6u0t2hhFdQvRBY7dqyGfdjmyjDqwc7ZOovHB0eqep.FPHrh8T9iz1LuucA; cf_clearance=rfIEldahI7B..Y4PpZhGgwi.QOJBqIRGdFP150.VnW-1766868784-1.1-"

    private const val G9R6_DOMAIN = "https://g9r6.com"
    private const val BYSEVEPOIN_DOMAIN = "https://bysevepoin.com"
    private const val FEMBED_DOMAIN = "https://fembed.sx"

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val (videoId, isSeries, cParam) = extractVideoIdAndType(url)
            if (videoId.isEmpty()) return false

            val m3u8Url = if (videoId.length > 10 && videoId.matches(Regex("[a-z0-9]+"))) {
                tryDirectG9r6Api(videoId, url)
            } else {
                tryFullFlow(videoId, isSeries, cParam, url)
            }

            if (m3u8Url == null) return false
            createExtractorLink(m3u8Url, name, callback)

        } catch (e: Exception) {
            false
        }
    }

    private fun extractVideoIdAndType(url: String): Triple<String, Boolean, String> {
        val cleanUrl = url.replace(Regex("""^https?://[^/]+"""), "")
        
        val patterns = listOf(
            Triple(Regex("""^/e/([a-zA-Z0-9]+)/(\d+-\d+)$"""), true, "formato /ID/TEMP-EP"),
            Triple(Regex("""^/e/([a-zA-Z0-9]+)\?c=(\d+-\d+)"""), true, "formato ?c="),
            Triple(Regex("""^/([a-zA-Z0-9]+)-c=(\d+-\d+)"""), true, "formato -c="),
            Triple(Regex("""^/e/([a-zA-Z0-9]+)(?:\?|$|/)"""), false, "filme padrão"),
            Triple(Regex("""^/v/([a-zA-Z0-9]+)(?:\?|$|/)"""), false, "filme /v/"),
            Triple(Regex("""/(\d+)$"""), false, "fallback numérico")
        )
        
        for ((pattern, isSeries, desc) in patterns) {
            val match = pattern.find(cleanUrl)
            if (match != null) {
                val id = match.groupValues[1]
                if (isSeries && match.groupValues.size > 2) {
                    val cParam = match.groupValues[2]
                    return Triple(id, true, cParam)
                } else {
                    return Triple(id, isSeries, if (isSeries) "1-1" else "")
                }
            }
        }
        
        return Triple("", false, "")
    }

    private suspend fun tryDirectG9r6Api(videoId: String, originalUrl: String): String? {
        return try {
            val headers = mapOf(
                "accept" to "*/*",
                "accept-language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "cache-control" to "no-cache",
                "pragma" to "no-cache",
                "priority" to "u=1, i",
                "referer" to "$G9R6_DOMAIN/bk2vx/$videoId",
                "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
                "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "x-embed-parent" to originalUrl,
                "cookie" to API_COOKIE
            )

            val apiUrl = "$G9R6_DOMAIN/api/videos/$videoId/embed/playback"
            val response = app.get(apiUrl, headers = headers)

            if (response.code != 200) return null
            processEncryptedResponse(response.text, videoId)

        } catch (e: Exception) {
            null
        }
    }

    private suspend fun tryFullFlow(
        shortVideoId: String, 
        isSeries: Boolean, 
        cParam: String, 
        originalUrl: String
    ): String? {
        return try {
            var iframeUrl = getFembedIframeWithLang(shortVideoId, isSeries, cParam, "DUB")
            if (iframeUrl == null) return null

            var bysevepoinUrl = getBysevepoinFromIframe(iframeUrl, shortVideoId, isSeries)
            
            if (bysevepoinUrl == null) {
                iframeUrl = getFembedIframeWithLang(shortVideoId, isSeries, cParam, "LEG")
                if (iframeUrl == null) return null
                bysevepoinUrl = getBysevepoinFromIframe(iframeUrl, shortVideoId, isSeries)
            }

            if (bysevepoinUrl == null) return null

            val realVideoId = extractRealVideoId(bysevepoinUrl)
            if (realVideoId == null) return null

            tryDirectG9r6Api(realVideoId, bysevepoinUrl)

        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getFembedIframeWithLang(
        videoId: String, 
        isSeries: Boolean = false, 
        cParam: String = "",
        lang: String
    ): String? {
        return try {
            val effectiveCParam = if (isSeries && cParam.isNotEmpty()) cParam else ""
            val apiUrl = "$FEMBED_DOMAIN/api.php?s=$videoId&c=$effectiveCParam"
            
            val headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$FEMBED_DOMAIN/e/$videoId${if (isSeries && cParam.isNotEmpty()) "?c=$cParam" else ""}",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Cookie" to API_COOKIE
            )
            
            val postData = mapOf(
                "action" to "getPlayer",
                "lang" to lang,
                "key" to "MA=="
            )
            
            val response = app.post(apiUrl, headers = headers, data = postData)
            val html = response.text
            
            val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            val match = iframePattern.find(html)
            
            if (match != null) {
                var url = match.groupValues[1]
                if (url.isBlank() || url == "\"\"" || url.contains("src=\"\"")) return null
                
                if (url.startsWith("/")) {
                    url = "$FEMBED_DOMAIN$url"
                }
                
                if (isSeries && !url.contains("c=") && cParam.isNotEmpty()) {
                    url = url.replace("&key=", "&c=$cParam&key=")
                }
                
                return url
            }
            
            null
            
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getBysevepoinFromIframe(
        iframeUrl: String, 
        videoId: String,
        isSeries: Boolean = false
    ): String? {
        return try {
            val headers = mapOf(
                "Referer" to "$FEMBED_DOMAIN/e/$videoId",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                "Cookie" to API_COOKIE
            )
            
            val response = app.get(iframeUrl, headers = headers)
            val html = response.text
            
            val hasBysevepoin = html.contains("bysevepoin") || 
                               html.contains("bysevepoin.com") ||
                               Regex("""https?://bysevepoin\.com""").containsMatchIn(html)
            
            if (!hasBysevepoin) return null
            
            val bysevepoinPattern = Regex("""<iframe[^>]+src=["'](https?://bysevepoin\.com/[^"']+)["']""")
            val match = bysevepoinPattern.find(html)
            
            if (match != null) {
                return match.groupValues[1]
            }
            
            null
            
        } catch (e: Exception) {
            null
        }
    }

    private fun extractRealVideoId(bysevepoinUrl: String): String? {
        val pattern = Regex("""/e/([a-zA-Z0-9]+)(?:/|$)""")
        val match = pattern.find(bysevepoinUrl)
        return match?.groupValues?.get(1)
    }

    private fun processEncryptedResponse(jsonText: String, videoId: String): String? {
        return try {
            val json = JSONObject(jsonText)
            val playback = json.getJSONObject("playback")

            fun decodeBase64(base64Str: String): ByteArray {
                val cleanStr = base64Str.trim()
                if (cleanStr.contains('-') || cleanStr.contains('_')) {
                    return Base64.decode(cleanStr, Base64.URL_SAFE or Base64.NO_PADDING)
                }
                try {
                    return Base64.decode(cleanStr, Base64.DEFAULT)
                } catch (e: IllegalArgumentException) {
                    return Base64.decode(cleanStr, Base64.NO_PADDING)
                }
            }

            val ivBase64 = playback.getString("iv")
            val payloadBase64 = playback.getString("payload")
            val keyParts = playback.getJSONArray("key_parts")

            val iv = decodeBase64(ivBase64)
            val payload = decodeBase64(payloadBase64)
            val key1 = decodeBase64(keyParts.getString(0))
            val key2 = decodeBase64(keyParts.getString(1))
            val key = key1 + key2

            val decrypted = decryptAesGcm(payload, key, iv)
            if (decrypted == null) return null

            val decryptedText = String(decrypted, Charsets.UTF_8)
            val decryptedJson = JSONObject(decryptedText)
            
            extractM3u8FromDecryptedJson(decryptedJson)

        } catch (e: Exception) {
            null
        }
    }

    private fun extractM3u8FromDecryptedJson(decryptedJson: JSONObject): String? {
        return try {
            if (decryptedJson.has("sources")) {
                val sources = decryptedJson.getJSONArray("sources")
                if (sources.length() > 0) {
                    val firstSource = sources.getJSONObject(0)
                    if (firstSource.has("url")) {
                        var url = firstSource.getString("url")
                        url = decodeUnicodeEscapes(url)
                        return url
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeUnicodeEscapes(text: String): String {
        return text.replace("\\u0026", "&")
    }

    private fun decryptAesGcm(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val actualIv = if (iv.size != 12) iv.copyOf(12) else iv
            val spec = GCMParameterSpec(128, actualIv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun createExtractorLink(
        m3u8Url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR",
                "Referer" to m3u8Url,
                "Range" to "bytes=0-"
            )

            val links = M3u8Helper.generateM3u8(
                source = "SuperFlix",
                streamUrl = m3u8Url,
                referer = m3u8Url,
                headers = headers
            )

            if (links.isNotEmpty()) {
                links.forEach { callback(it) }
                return true
            }

            val fallbackLink = newExtractorLink(
                source = "SuperFlix",
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = m3u8Url
                this.quality = 720
                this.headers = headers
            }
            
            callback(fallbackLink)
            return true

        } catch (e: Exception) {
            return false
        }
    }
}
