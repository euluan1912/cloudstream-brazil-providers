package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/documentario/" to "Documentário",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/familia/" to "Família",
        "$mainUrl/category/fantasia/" to "Fantasia",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/guerra/" to "Guerra",
        "$mainUrl/category/kids/" to "Kids",
        "$mainUrl/category/misterio/" to "Misterio",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "page/$page/" else ""
        val document = app.get(url).document
        val home = document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("header.entry-header h2.entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null

        val posterUrl = selectFirst("div.post-thumbnail figure img")?.attr("src")
            ?.takeIf { it.isNotBlank() } 
            ?.let { fixUrl(it).replace("/w500/", "/original/") }
            ?: selectFirst("div.post-thumbnail figure img")?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it).replace("/w500/", "/original/") }

        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(selectFirst("span.post-ql")?.text())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return null

        val poster = document.selectFirst("div.bghd img.TPostBg")?.attr("src")
            ?.takeIf { it.isNotBlank() } 
            ?.let { fixUrl(it).replace("/w1280/", "/original/") }
            ?: document.selectFirst("div.bghd img.TPostBg")?.attr("data-src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it).replace("/w1280/", "/original/") }

        val yearText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.year")?.ownText()
        val year = yearText?.toIntOrNull()
        val durationText = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.ownText()
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val tags = document.select("aside.fg1 header.entry-header div.entry-meta span.genres a").map { it.text() }
        val actors = document.select("aside.fg1 ul.cast-lst p a").map {
            Actor(it.text(), it.attr("href"))
        }
        val trailer = document.selectFirst("div.mdl-cn div.video iframe")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("div.mdl-cn div.video iframe")?.attr("data-src")

        val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("src")
            ?.takeIf { it.isNotBlank() } ?: document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("data-src")

        val isSerie = url.contains("/serie/")

        return if (isSerie) {
            val episodes = if (iframeUrl != null) {
                try {
                    val iframeDoc = app.get(iframeUrl).document
                    parseSeriesEpisodes(iframeDoc)
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = null
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = parseDuration(durationText)
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    // FUNÇÃO SIMPLIFICADA PARA EXTRAIR EPISÓDIOS
    private fun parseSeriesEpisodes(doc: org.jsoup.nodes.Document): List<Episode> {
        val episodes = mutableListOf<Episode>()

        println("=== ANALISANDO EPISÓDIOS ===")

        // Procura por links de episódios
        doc.select("a[href*='/episodio/']").forEach { link ->
            val href = link.attr("href")
            val title = link.text().trim()
            
            if (title.isNotBlank() && href.isNotBlank()) {
                println("🎬 Encontrado: $title -> $href")
                
                // Tenta extrair temporada e episódio do título
                var season = 1
                var episode = 1
                
                val seasonMatch = Regex("""T(\d+)""", RegexOption.IGNORE_CASE).find(title)
                val episodeMatch = Regex("""E(\d+)""", RegexOption.IGNORE_CASE).find(title)
                
                season = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                episodes.add(
                    Episode(
                        data = href,
                        name = title,
                        season = season,
                        episode = episode,
                        posterUrl = null
                    )
                )
            }
        }

        println("\n✅ Total de episódios encontrados: ${episodes.size}")
        return episodes
    }

    private fun parseDuration(duration: String?): Int? {
        if (duration.isNullOrBlank()) return null
        val regex = Regex("""(\d+)h.*?(\d+)m""")
        val match = regex.find(duration)
        return if (match != null) {
            val h = match.groupValues[1].toIntOrNull() ?: 0
            val m = match.groupValues[2].toIntOrNull() ?: 0
            h * 60 + m
        } else {
            Regex("""(\d+)m""").find(duration)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    // loadLinks CORRIGIDO para lidar com anúncios
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 ULTRA CINE loadLinks CHAMADO")
        println("📦 Data recebido: $data")
        
        if (data.isBlank()) return false

        return try {
            // URL final a ser usada
            val finalUrl = when {
                data.startsWith("https://") -> data
                data.startsWith("http://") -> data
                else -> "https://assistirseriesonline.icu/episodio/$data"
            }
            
            println("🔗 Acessando URL: $finalUrl")
            
            // PRIMEIRA TENTATIVA: Acesso direto com headers
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
                "Referer" to mainUrl,
                "DNT" to "1",
                "Connection" to "keep-alive",
                "Upgrade-Insecure-Requests" to "1"
            )
            
            val res = app.get(finalUrl, headers = headers, timeout = 60)
            val doc = res.document
            
            // ANALISA A PÁGINA PARA ENCONTRAR O VÍDEO
            return analyzePageForVideo(doc, finalUrl, callback)
            
        } catch (e: Exception) {
            println("💥 ERRO no loadLinks: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // FUNÇÃO PARA ANALISAR A PÁGINA E ENCONTRAR O VÍDEO
    private suspend fun analyzePageForVideo(
        doc: org.jsoup.nodes.Document,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 Analisando página em busca de vídeo...")
        
        // ESTRATÉGIA 1: Procura por iframes
        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            println("🖼️ Iframe encontrado: $src")
            
            // Tenta extrair do iframe
            if (tryExtractFromIframe(src, referer, callback)) {
                return true
            }
        }
        
        // ESTRATÉGIA 2: Procura por scripts com URLs de vídeo
        doc.select("script").forEach { script ->
            val scriptText = script.html()
            
            // Procura por URLs de m3u8
            val m3u8Matches = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""").findAll(scriptText).toList()
            for (match in m3u8Matches) {
                val m3u8Url = match.groupValues[1]
                println("🎬 M3U8 encontrado no script: $m3u8Url")
                
                if (createExtractorLink(m3u8Url, referer, callback, true)) {
                    return true
                }
            }
            
            // Procura por URLs de MP4
            val mp4Matches = Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)""").findAll(scriptText).toList()
            for (match in mp4Matches) {
                val mp4Url = match.groupValues[1]
                println("🎬 MP4 encontrado no script: $mp4Url")
                
                if (createExtractorLink(mp4Url, referer, callback, false)) {
                    return true
                }
            }
        }
        
        // ESTRATÉGIA 3: Procura por elementos de vídeo HTML5
        doc.select("video source[src]").forEach { source ->
            val videoUrl = source.attr("src")
            println("🎬 Vídeo HTML5 encontrado: $videoUrl")
            
            if (createExtractorLink(videoUrl, referer, callback, videoUrl.contains(".m3u8"))) {
                return true
            }
        }
        
        // ESTRATÉGIA 4: Procura por links que possam conter vídeo
        doc.select("a[href*='.m3u8'], a[href*='.mp4']").forEach { link ->
            val videoUrl = link.attr("href")
            println("🔗 Link de vídeo encontrado: $videoUrl")
            
            if (createExtractorLink(videoUrl, referer, callback, videoUrl.contains(".m3u8"))) {
                return true
            }
        }
        
        // ESTRATÉGIA 5: Tenta seguir redirecionamentos
        val allLinks = doc.select("a[href]")
        for (link in allLinks) {
            val href = link.attr("href")
            if (href.contains("player") || href.contains("video") || href.contains("embed")) {
                println("🔄 Seguindo link suspeito: $href")
                
                try {
                    val newRes = app.get(href, referer = referer, timeout = 30)
                    val newDoc = newRes.document
                    
                    if (analyzePageForVideo(newDoc, href, callback)) {
                        return true
                    }
                } catch (e: Exception) {
                    println("❌ Erro ao seguir link: ${e.message}")
                }
            }
        }
        
        println("❌ Nenhum vídeo encontrado na página")
        return false
    }
    
    // FUNÇÃO PARA TENTAR EXTRAIR DE UM IFRAME
    private suspend fun tryExtractFromIframe(
        iframeSrc: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (iframeSrc.isBlank()) return false
        
        println("🔍 Extraindo do iframe: $iframeSrc")
        
        try {
            // Adiciona headers para evitar bloqueios
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to referer,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            
            val res = app.get(iframeSrc, headers = headers, timeout = 30)
            val html = res.text
            
            // Procura por URLs de vídeo
            val videoPatterns = listOf(
                Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
                Regex("""(https?://[^"'\s]+\.mp4[^"'\s]*)"""),
                Regex("""['"]file['"]\s*:\s*['"](https?://[^"']+)['"]"""),
                Regex("""['"]src['"]\s*:\s*['"](https?://[^"']+)['"]"""),
                Regex("""<source[^>]+src=['"](https?://[^"']+)['"]""")
            )
            
            for (pattern in videoPatterns) {
                pattern.findAll(html).forEach { match ->
                    val videoUrl = match.groupValues[1]
                    if (videoUrl.isNotBlank() && 
                        (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4") || videoUrl.contains(".mkv"))) {
                        
                        println("🎬 Vídeo encontrado no iframe: $videoUrl")
                        
                        return createExtractorLink(videoUrl, iframeSrc, callback, videoUrl.contains(".m3u8"))
                    }
                }
            }
        } catch (e: Exception) {
            println("❌ Erro ao extrair do iframe: ${e.message}")
        }
        
        return false
    }
    
    // FUNÇÃO AUXILIAR PARA CRIAR EXTRACTOR LINK
    private fun createExtractorLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        isM3u8: Boolean
    ): Boolean {
        if (url.isBlank()) return false
        
        try {
            val quality = when {
                url.contains("360p") -> 360
                url.contains("480p") -> 480
                url.contains("720p") -> 720
                url.contains("1080p") -> 1080
                url.contains("2160p") -> 2160
                else -> Qualities.Unknown.value
            }
            
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} (${if (isM3u8) "HLS" else "Direct"})",
                    url = url,
                    referer = referer,
                    quality = quality,
                    isM3u8 = isM3u8
                )
            )
            
            println("✅ ExtractorLink criado com sucesso!")
            return true
        } catch (e: Exception) {
            println("❌ Erro ao criar ExtractorLink: ${e.message}")
            return false
        }
    }
}