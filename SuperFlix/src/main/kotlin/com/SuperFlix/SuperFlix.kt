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
                        ?: link.selectFirst(".rec-title, .title, h2, h3)")?.text()
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
            val episodes = extractEpisodesFromButtons(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        } else {
            // Para filmes, buscar diretamente a URL do Fembed
            val fembedUrl = findFembedUrl(document)
            
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

    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        document.select("button.bd-play[data-url]").forEach { button ->
            val fembedUrl = button.attr("data-url")
            val season = button.attr("data-season").toIntOrNull() ?: 1
            val episodeNum = button.attr("data-ep").toIntOrNull() ?: 1

            var episodeTitle = "Episódio $episodeNum"

            val parent = button.parents().find { it.hasClass("episode-item") || it.hasClass("episode") }
            parent?.let {
                val titleElement = it.selectFirst(".ep-title, .title, .name, h3, h4)
                if (titleElement != null) {
                    episodeTitle = titleElement.text().trim()
                }
            }

            episodes.add(
                newEpisode(fembedUrl) {
                    this.name = episodeTitle
                    this.season = season
                    this.episode = episodeNum
                }
            )
        }

        return episodes
    }

    private fun findFembedUrl(document: org.jsoup.nodes.Document): String? {
        // Tentar encontrar URL do Fembed de várias maneiras
        val patterns = listOf(
            { doc: org.jsoup.nodes.Document -> 
                val iframe = doc.selectFirst("iframe[src*='fembed']")
                iframe?.attr("src")
            },
            { doc: org.jsoup.nodes.Document ->
                val playButton = doc.selectFirst("button.bd-play[data-url]")
                playButton?.attr("data-url")
            },
            { doc: org.jsoup.nodes.Document ->
                val anyButton = doc.selectFirst("button[data-url*='fembed']")
                anyButton?.attr("data-url")
            },
            { doc: org.jsoup.nodes.Document ->
                val html = doc.html()
                val patterns = listOf(
                    Regex("""https?://[^"'\s]*fembed[^"'\s]*/e/\w+"""),
                    Regex("""data-url=["'](https?://[^"']*fembed[^"']+)["']"""),
                    Regex("""src\s*[:=]\s*["'](https?://[^"']*fembed[^"']+)["']""")
                )
                
                patterns.forEach { pattern ->
                    pattern.find(html)?.let { match ->
                        val url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                        if (url.isNotBlank()) return@forEach url
                    }
                }
                null
            }
        )
        
        for (pattern in patterns) {
            val url = pattern(document)
            if (url != null && url.isNotBlank()) {
                return url
            }
        }
        
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Carregando links de: $data")
        
        // Se a URL já for uma URL direta (não do Fembed), tentar extrair diretamente
        if (!data.contains("fembed")) {
            println("SuperFlix: URL não é do Fembed, tentando extração direta")
            // Tentar extrair como link direto
            if (data.contains("http") && data.contains(".m3u8")) {
                println("SuperFlix: Parece ser um link m3u8 direto")
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "$name (Direto)",
                        url = data
                    )
                )
                return true
            }
            return false
        }
        
        // URL do Fembed - simplificar drasticamente
        return try {
            extractFembedSimple(data, callback)
        } catch (e: Exception) {
            println("SuperFlix: ERRO - ${e.message}")
            false
        }
    }

    private suspend fun extractFembedSimple(
        fembedUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Extraindo vídeo do Fembed: $fembedUrl")
        
        // Método 1: Usar a URL diretamente como player
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to mainUrl,
            "Origin" to mainUrl
        )
        
        try {
            // Tentar acessar a página do Fembed
            val response = app.get(fembedUrl, headers = headers)
            println("SuperFlix: Status página Fembed: ${response.code}")
            
            if (response.isSuccessful) {
                val html = response.text
                println("SuperFlix: Página obtida (${html.length} chars)")
                
                // Procurar por player.js ou scripts com dados
                val playerScript = findPlayerScript(html)
                if (playerScript != null) {
                    println("SuperFlix: Script do player encontrado")
                    
                    // Extrair dados do script
                    val videoData = extractVideoDataFromScript(playerScript)
                    if (videoData != null) {
                        println("SuperFlix: Dados do vídeo encontrados: $videoData")
                        
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "$name (Fembed)",
                                url = videoData
                            )
                        )
                        return true
                    }
                }
                
                // Tentar encontrar iframe com player real
                val iframeSrc = findPlayerIframe(html)
                if (iframeSrc != null) {
                    println("SuperFlix: Iframe encontrado: $iframeSrc")
                    
                    // Tentar acessar o iframe
                    val iframeResponse = app.get(iframeSrc, headers = headers)
                    if (iframeResponse.isSuccessful) {
                        val iframeHtml = iframeResponse.text
                        val directVideo = findDirectVideoUrl(iframeHtml)
                        
                        if (directVideo != null) {
                            println("SuperFlix: Vídeo direto encontrado: $directVideo")
                            
                            callback(
                                newExtractorLink(
                                    source = this.name,
                                    name = "$name (Player)",
                                    url = directVideo
                                )
                            )
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("SuperFlix: Erro ao acessar Fembed: ${e.message}")
        }
        
        // Método 2: Usar serviço alternativo (streamtape)
        println("SuperFlix: Tentando converter para Streamtape...")
        val streamtapeUrl = convertToStreamtape(fembedUrl)
        if (streamtapeUrl != null) {
            println("SuperFlix: URL Streamtape: $streamtapeUrl")
            
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "$name (Streamtape)",
                    url = streamtapeUrl
                )
            )
            return true
        }
        
        // Método 3: Usar serviço alternativo (voe)
        println("SuperFlix: Tentando converter para VOE...")
        val voeUrl = convertToVoe(fembedUrl)
        if (voeUrl != null) {
            println("SuperFlix: URL VOE: $voeUrl")
            
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "$name (VOE)",
                    url = voeUrl
                )
            )
            return true
        }
        
        println("SuperFlix: Nenhum método funcionou")
        return false
    }

    private fun findPlayerScript(html: String): String? {
        // Procurar por scripts que contenham dados do player
        val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        val scripts = scriptPattern.findAll(html)
        
        for (match in scripts) {
            val script = match.groupValues[1]
            if (script.contains("player_data") || 
                script.contains("sources") || 
                script.contains("file\":") || 
                script.contains("m3u8")) {
                return script
            }
        }
        return null
    }

    private fun extractVideoDataFromScript(script: String): String? {
        // Padrões para URLs de vídeo
        val patterns = listOf(
            Regex("""["']file["']\s*:\s*["']([^"']+\.(?:m3u8|mp4|mkv))["']"""),
            Regex("""["']url["']\s*:\s*["']([^"']+\.(?:m3u8|mp4|mkv))["']"""),
            Regex("""["']src["']\s*:\s*["']([^"']+\.(?:m3u8|mp4|mkv))["']"""),
            Regex("""sources\s*:\s*\[[^\]]*["']([^"']+\.(?:m3u8|mp4|mkv))["'][^\]]*\]""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(script)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun findPlayerIframe(html: String): String? {
        val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
        val match = iframePattern.find(html)
        return match?.groupValues?.get(1)
    }

    private fun findDirectVideoUrl(html: String): String? {
        // Procurar por tags de vídeo ou source
        val videoPattern = Regex("""<video[^>]+src=["']([^"']+)["']""")
        val videoMatch = videoPattern.find(html)
        if (videoMatch != null) return videoMatch.groupValues[1]
        
        val sourcePattern = Regex("""<source[^>]+src=["']([^"']+)["']""")
        val sourceMatch = sourcePattern.find(html)
        if (sourceMatch != null) return sourceMatch.groupValues[1]
        
        // Procurar por URLs .m3u8 ou .mp4
        val urlPattern = Regex("""https?://[^"'\s]+\.(?:m3u8|mp4|mkv)[^"'\s]*""")
        val urlMatch = urlPattern.find(html)
        return urlMatch?.value
    }

    private fun convertToStreamtape(fembedUrl: String): String? {
        // Extrair ID do Fembed
        val idMatch = Regex("""/e/(\d+)""").find(fembedUrl)
        val id = idMatch?.groupValues?.get(1) ?: return null
        
        // Converter para streamtape (URL exemplo)
        return "https://streamtape.com/e/$id"
    }

    private fun convertToVoe(fembedUrl: String): String? {
        // Extrair ID do Fembed
        val idMatch = Regex("""/e/(\d+)""").find(fembedUrl)
        val id = idMatch?.groupValues?.get(1) ?: return null
        
        // Converter para voe (URL exemplo)
        return "https://voe.sx/e/$id"
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
}