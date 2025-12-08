package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document
        
        val home = mutableListOf<SearchResponse>()
        
        document.select("div.recs-grid a.rec-card, .movie-card, article, .item").forEach { element ->
            element.toSearchResult()?.let { home.add(it) }
        }
        
        if (home.isEmpty()) {
            document.select("a[href*='/filme/'], a[href*='/serie/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !href.contains("#")) {
                    val title = link.selectFirst("img")?.attr("alt")
                        ?: link.selectFirst(".rec-title, .title, h2, h3")?.text()
                        ?: href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()
                    
                    if (title.isNotBlank()) {
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        val poster = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                        val isSerie = href.contains("/serie/")
                        
                        val searchResponse = if (isSerie) {
                            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        } else {
                            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        }
                        
                        home.add(searchResponse)
                    }
                }
            }
        }
        
        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".rec-title, .movie-title, h2, h3, .title")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: return null
            
        val href = attr("href") ?: selectFirst("a")?.attr("href") ?: return null
        
        val poster = selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?: selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }
        
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: selectFirst(".rec-meta, .movie-year, .year")?.text()?.let { 
                Regex("\\b(\\d{4})\\b").find(it)?.groupValues?.get(1)?.toIntOrNull() 
            }
        
        val isSerie = href.contains("/serie/")
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document
        
        val results = mutableListOf<SearchResponse>()
        
        document.select("div.recs-grid a.rec-card, a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
            element.toSearchResult()?.let { results.add(it) }
        }
        
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val html = document.html()

        val jsonLd = extractJsonLd(html)
        
        val title = jsonLd.title ?: document.selectFirst("h1, .title")?.text() ?: return null
        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")
        
        val plot = jsonLd.description ?: document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst(".syn, .description")?.text()
        
        val tags = jsonLd.genres ?: document.select("a.chip, .chip").map { it.text() }
        
        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()
        
        val director = jsonLd.director?.firstOrNull()
        
        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"
        
        return if (isSerie) {
            val episodes = extractEpisodesWithFembedLinks(document)
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        } else {
            val fembedUrl = extractFembedUrlFromPage(document, html)
            
            newMovieLoadResponse(title, url, TvType.Movie, fembedUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        }
    }

    private fun extractEpisodesWithFembedLinks(document: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        document.select("button.bd-play[data-url]").forEach { button ->
            val fembedUrl = button.attr("data-url")
            val season = button.attr("data-season").toIntOrNull() ?: 1
            val episodeNum = button.attr("data-ep").toIntOrNull() ?: 1
            
            episodes.add(
                newEpisode(fembedUrl) {
                    this.name = "Episódio $episodeNum"
                    this.season = season
                    this.episode = episodeNum
                }
            )
        }
        
        return episodes
    }

    private fun extractFembedUrlFromPage(document: org.jsoup.nodes.Document, html: String): String? {
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }
        
        val iframe = document.selectFirst("iframe[src*='fembed']")
        if (iframe != null) {
            return iframe.attr("src")
        }
        
        val patterns = listOf(
            Regex("""https?://fembed\.sx/e/\d+[^"'\s]*"""),
            Regex("""data-url=["'](https?://[^"']+fembed[^"']+)["']"""),
            Regex("""src\s*[:=]\s*["'](https?://[^"']+fembed[^"']+)["']""")
        )
        
        patterns.forEach { pattern ->
            pattern.find(html)?.let { match ->
                val url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                if (url.isNotBlank()) return url
            }
        }
        
        return null
    }

    private data class JsonLdInfo(
        val title: String? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val director: List<String>? = null,
        val actors: List<String>? = null,
        val tmdbId: String? = null,
        val type: String? = null
    )

    private fun extractJsonLd(html: String): JsonLdInfo {
        val pattern = Regex("<script type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        val matches = pattern.findAll(html)
        
        matches.forEach { match ->
            try {
                val json = match.groupValues[1].trim()
                if (json.contains("\"@type\":\"Movie\"") || json.contains("\"@type\":\"TVSeries\"")) {
                    
                    val title = Regex("\"name\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val image = Regex("\"image\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val description = Regex("\"description\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    
                    val genresMatch = Regex("\"genre\":\\s*\\[([^\\]]+)\\]").find(json)
                    val genres = genresMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.map { it.trim().trim('"', '\'') }
                        ?.filter { it.isNotBlank() }
                    
                    val actorsMatch = Regex("\"actor\":\\s*\\[([^\\]]+)\\]").find(json)
                    val actors = actorsMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { actor ->
                            Regex("\"name\":\"([^\"]+)\"").find(actor)?.groupValues?.get(1)
                        }
                    
                    val directorMatch = Regex("\"director\":\\s*\\[([^\\]]+)\\]").find(json)
                    val director = directorMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { dir ->
                            Regex("\"name\":\"([^\"]+)\"").find(dir)?.groupValues?.get(1)
                        }
                    
                    val sameAsMatch = Regex("\"sameAs\":\\s*\\[([^\\]]+)\\]").find(json)
                    val tmdbId = sameAsMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.find { it.contains("themoviedb.org") }
                        ?.substringAfterLast("/")
                        ?.trim(' ', '"', '\'')
                    
                    val type = if (json.contains("\"@type\":\"Movie\"")) "Movie" else "TVSeries"
                    
                    return JsonLdInfo(
                        title = title,
                        year = null,
                        posterUrl = image,
                        description = description,
                        genres = genres,
                        director = director,
                        actors = actors,
                        tmdbId = tmdbId,
                        type = type
                    )
                }
            } catch (e: Exception) {
                // Continua
            }
        }
        
        return JsonLdInfo()
    }

    // 🔥🔥🔥 NOVA FUNÇÃO loadLinks COM EXTRACTION PRÓPRIA 🔥🔥🔥
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        
        return try {
            println("SuperFlix: loadLinks chamado com data = $data")
            
            // 🔥 SE JÁ FOR LINK .m3u8 DIRETO (formato que encontramos)
            if (data.contains(".m3u8") && data.contains("/hls2/")) {
                println("SuperFlix: Link .m3u8 direto encontrado!")
                val quality = extractQualityFromUrl(data)
                
                @Suppress("DEPRECATION")
                val link = ExtractorLink(
                    source = name,
                    name = "$name (${quality}p)",
                    url = data,
                    referer = mainUrl,
                    quality = quality,
                    isM3u8 = true
                )
                callback.invoke(link)
                return true
            }
            
            // 🔥 SE FOR URL DO FEMBED (botão PLAY)
            if (data.contains("fembed.sx")) {
                println("SuperFlix: URL do Fembed encontrada: $data")
                return extractDirectVideoFromFembed(data, callback)
            }
            
            // 🔥 SE FOR URL DO SUPERFLIX
            val finalUrl = if (data.startsWith("http")) data else fixUrl(data)
            println("SuperFlix: Fazendo requisição para: $finalUrl")
            
            val res = app.get(finalUrl, referer = mainUrl, timeout = 30)
            val html = res.text
            
            // Procurar URL do Fembed na página
            val fembedUrl = findFembedUrlInHtml(html)
            
            if (fembedUrl != null) {
                println("SuperFlix: Fembed encontrado na página: $fembedUrl")
                return extractDirectVideoFromFembed(fembedUrl, callback)
            }
            
            println("SuperFlix: Nenhum link encontrado")
            false
            
        } catch (e: Exception) {
            println("SuperFlix: Erro no loadLinks: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // 🔥 FUNÇÃO PARA EXTRAIR VÍDEO DIRETO DO FEMBED
    private suspend fun extractDirectVideoFromFembed(fembedUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            println("SuperFlix: Extraindo vídeo do Fembed: $fembedUrl")
            
            // Fazer requisição para a página do Fembed
            val res = app.get(fembedUrl, referer = mainUrl, timeout = 30)
            val html = res.text
            
            // 🔥 PADRÃO 1: Procurar o player principal
            val playerPatterns = listOf(
                Regex("""<div[^>]+id=["']player["'][^>]*>.*?<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL),
                Regex("""jwplayer\(["'][^"']+["']\)\.setup\(({[^}]+})\)"""),
                Regex("""playerInstance\.setup\(({[^}]+})\)""")
            )
            
            for (pattern in playerPatterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val playerScript = match.groupValues[1]
                    println("SuperFlix: Player script encontrado (${playerScript.length} chars)")
                    
                    // Dentro do player, procurar por sources/file
                    val sourcePatterns = listOf(
                        Regex("""sources\s*:\s*\[([^\]]+)\]"""),
                        Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                        Regex("""src\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
                    )
                    
                    for (sourcePattern in sourcePatterns) {
                        val sourceMatch = sourcePattern.find(playerScript)
                        if (sourceMatch != null) {
                            var m3u8Url = if (sourceMatch.groupValues.size > 1) sourceMatch.groupValues[1] else sourceMatch.value
                            
                            // Limpar se for array JSON
                            if (m3u8Url.contains("[")) {
                                val urlMatch = Regex("""https?://[^"',\s]+\.m3u8[^"',\s]*""").find(m3u8Url)
                                m3u8Url = urlMatch?.value ?: continue
                            }
                            
                            if (m3u8Url.isNotBlank() && m3u8Url.contains("/hls2/")) {
                                println("SuperFlix: URL .m3u8 encontrada no player: $m3u8Url")
                                val quality = extractQualityFromUrl(m3u8Url)
                                
                                @Suppress("DEPRECATION")
                                val link = ExtractorLink(
                                    source = name,
                                    name = "$name (${quality}p)",
                                    url = m3u8Url,
                                    referer = mainUrl,
                                    quality = quality,
                                    isM3u8 = true
                                )
                                callback.invoke(link)
                                return true
                            }
                        }
                    }
                }
            }
            
            // 🔥 PADRÃO 2: Procurar em scripts JavaScript
            val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            val scriptMatches = scriptPattern.findAll(html)
            
            for (scriptMatch in scriptMatches) {
                val script = scriptMatch.groupValues[1]
                if (script.contains("/hls2/") && script.contains(".m3u8")) {
                    // Procurar URL completa
                    val urlPattern = Regex("""(https?://[^"'\s]+/hls2/[^"'\s]+\.m3u8[^"'\s]*)""")
                    val urlMatch = urlPattern.find(script)
                    
                    if (urlMatch != null) {
                        val m3u8Url = urlMatch.value
                        println("SuperFlix: URL .m3u8 encontrada em script: $m3u8Url")
                        val quality = extractQualityFromUrl(m3u8Url)
                        
                        @Suppress("DEPRECATION")
                        val link = ExtractorLink(
                            source = name,
                            name = "$name (${quality}p)",
                            url = m3u8Url,
                            referer = mainUrl,
                            quality = quality,
                            isM3u8 = true
                        )
                        callback.invoke(link)
                        return true
                    }
                }
            }
            
            // 🔥 PADRÃO 3: Tentar API do Fembed (último recurso)
            println("SuperFlix: Tentando chamada de API do Fembed...")
            
            // Extrair ID do Fembed da URL
            val fembedId = extractFembedId(fembedUrl)
            if (fembedId != null) {
                println("SuperFlix: ID do Fembed extraído: $fembedId")
                
                // Tentar API conhecida do Fembed
                val apiUrl = "https://fembed.sx/api/source/$fembedId"
                val apiResponse = app.post(
                    apiUrl,
                    headers = mapOf(
                        "Referer" to fembedUrl,
                        "Origin" to "https://fembed.sx",
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    data = mapOf("r" to ""),
                    timeout = 30
                )
                
                val apiText = apiResponse.text
                println("SuperFlix: Resposta da API (${apiText.length} chars)")
                
                // Procurar links na resposta da API
                val apiPattern = Regex(""""(https?://[^"]+/hls2/[^"]+\.m3u8[^"]*)"""")
                val apiMatches = apiPattern.findAll(apiText)
                
                for (apiMatch in apiMatches) {
                    val m3u8Url = apiMatch.groupValues[1]
                    if (m3u8Url.isNotBlank()) {
                        println("SuperFlix: URL .m3u8 da API: $m3u8Url")
                        val quality = extractQualityFromUrl(m3u8Url)
                        
                        @Suppress("DEPRECATION")
                        val link = ExtractorLink(
                            source = name,
                            name = "$name (${quality}p)",
                            url = m3u8Url,
                            referer = mainUrl,
                            quality = quality,
                            isM3u8 = true
                        )
                        callback.invoke(link)
                        return true
                    }
                }
            }
            
            println("SuperFlix: Nenhum link .m3u8 encontrado no Fembed")
            false
            
        } catch (e: Exception) {
            println("SuperFlix: Erro ao extrair do Fembed: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    private fun findFembedUrlInHtml(html: String): String? {
        val patterns = listOf(
            Regex("""data-url=["'](https?://[^"']+fembed[^"']+)["']"""),
            Regex("""<iframe[^>]+src=["'](https?://[^"']+fembed[^"']+)["']"""),
            Regex("""https?://fembed\.sx/e/\d+[^"'\s]*""")
        )
        
        patterns.forEach { pattern ->
            pattern.find(html)?.let { match ->
                val url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                if (url.isNotBlank()) return url
            }
        }
        
        return null
    }
    
    private fun extractFembedId(url: String): String? {
        val patterns = listOf(
            Regex("""fembed\.sx/e/(\d+)/?"""),
            Regex("""fembed\.com/v/(\d+)/?""")
        )
        
        patterns.forEach { pattern ->
            pattern.find(url)?.groupValues?.get(1)?.let { id ->
                return id
            }
        }
        
        return null
    }
    
    private fun extractQualityFromUrl(url: String): Int {
        return when {
            url.contains("/2160/") || url.contains("2160p") -> 2160
            url.contains("/1080/") || url.contains("1080p") -> 1080
            url.contains("/720/") || url.contains("720p") -> 720
            url.contains("/480/") || url.contains("480p") -> 480
            url.contains("/360/") || url.contains("360p") -> 360
            else -> Qualities.Unknown.value
        }
    }
}