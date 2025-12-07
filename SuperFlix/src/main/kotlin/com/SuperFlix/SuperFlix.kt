package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix21"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/acao" to "Ação",
        "$mainUrl/animacao" to "Animação",
        "$mainUrl/aventura" to "Aventura",
        "$mainUrl/comedia" to "Comédia",
        "$mainUrl/documentario" to "Documentário",
        "$mainUrl/drama" to "Drama",
        "$mainUrl/terror" to "Terror",
        "$mainUrl/suspense" to "Suspense"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document
        
        val home = document.select("div.movie-card, article, .item").mapNotNull {
            it.toSearchResult()
        }
        
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("div.movie-title, h2, h3, .title")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        
        val posterUrl = selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() } 
            ?.let { fixUrl(it) }
        
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: selectFirst(".movie-year, .year")?.text()?.toIntOrNull()
        
        val quality = selectFirst("div.quality-tag, .quality")?.text()
        val isSerie = href.contains("/serie/")
        
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = getQualityFromString(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.movie-card, article, .item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val html = document.html()

        // Extrair título
        val title = document.selectFirst("h1")?.text() 
            ?: Regex("<title>(.*?)</title>").find(html)?.groupValues?.get(1)
            ?: return null
        
        // Extrair ano do título
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        // Extrair poster (meta tag ou imagem)
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")
            ?: document.selectFirst("img[src*='tmdb']")?.attr("src")?.let { fixUrl(it) }
        
        // Extrair sinopse
        val plot = document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst(".description, .sinopse, p")?.text()
        
        // Extrair gêneros
        val tags = mutableListOf<String>()
        val genrePattern = Regex("\"genre\":\\s*\\[([^\\]]+)\\]")
        genrePattern.find(html)?.groupValues?.get(1)?.let { genreStr ->
            genreStr.split(",").forEach { tag ->
                val cleanTag = tag.trim().trim('"', '\'', ' ')
                if (cleanTag.isNotBlank()) tags.add(cleanTag)
            }
        }
        
        // Extrair atores
        val actors = mutableListOf<Actor>()
        val actorPattern = Regex("\"actor\":\\s*\\[([^\\]]+)\\]")
        actorPattern.find(html)?.groupValues?.get(1)?.let { actorStr ->
            val actorNames = Regex("\"name\":\"([^\"]+)\"").findAll(actorStr)
            actorNames.forEach { match ->
                val name = match.groupValues[1]
                if (name.isNotBlank()) {
                    actors.add(Actor(name, ""))
                }
            }
        }
        
        // Extrair TMDB ID para Fembed
        val tmdbId = extractTmdbId(html)
        val fembedUrl = if (tmdbId != null) {
            "https://fembed.sx/e/$tmdbId"
        } else {
            // Procurar iframe do Fembed
            document.selectFirst("iframe[src*='fembed']")?.attr("src")
        }
        
        // Verificar se é série
        val isSerie = url.contains("/serie/")
        
        return if (isSerie) {
            val episodes = extractEpisodes(document, tmdbId)
            
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, fembedUrl ?: "") {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                addActors(actors)
            }
        }
    }

    private fun extractTmdbId(html: String): String? {
        // Procura por TMDB ID
        val patterns = listOf(
            Regex("themoviedb.org/movie/(\\d+)"),
            Regex("tmdb.org/movie/(\\d+)"),
            Regex("""data-id=["'](\d+)["']"""),
            Regex("""\b(\d{6,7})\b""") // TMDB IDs geralmente têm 6-7 dígitos
        )
        
        patterns.forEach { pattern ->
            pattern.find(html)?.groupValues?.get(1)?.let { id ->
                if (id.isNotBlank() && id.length in 4..10) {
                    return id
                }
            }
        }
        
        return null
    }

    private fun extractEpisodes(document: org.jsoup.nodes.Document, tmdbId: String?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Tentar extrair episódios
        document.select(".season, .temporada").forEachIndexed { seasonIndex, season ->
            val seasonNum = seasonIndex + 1
            
            season.select(".episode, .episodio").forEachIndexed { epIndex, ep ->
                val epTitle = ep.selectFirst(".title, .name")?.text() ?: "Episódio ${epIndex + 1}"
                val epUrl = ep.selectFirst("a")?.attr("href") ?: ""
                val epNum = ep.selectFirst(".number, .ep")?.text()?.toIntOrNull() ?: (epIndex + 1)
                
                // Criar URL do Fembed para episódio
                val finalUrl = if (tmdbId != null && epUrl.isBlank()) {
                    "https://fembed.sx/e/$tmdbId?ep=$epNum"
                } else {
                    fixUrl(epUrl)
                }
                
                if (finalUrl.isNotBlank()) {
                    episodes.add(
                        newEpisode(finalUrl) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epNum
                        }
                    )
                }
            }
        }
        
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        
        return try {
            // ESTRATÉGIA 1: Se já é URL do Fembed
            if (data.contains("fembed.sx")) {
                return loadExtractor(data, mainUrl, subtitleCallback, callback)
            }
            
            // ESTRATÉGIA 2: Se é URL do SuperFlix
            val finalUrl = if (data.startsWith("http")) data else fixUrl(data)
            val res = app.get(finalUrl, referer = mainUrl)
            val html = res.text
            
            // Procurar Fembed
            val fembedPattern = Regex("""https?://fembed\.sx/e/\d+""")
            val fembedMatch = fembedPattern.find(html)
            
            if (fembedMatch != null) {
                return loadExtractor(fembedMatch.value, finalUrl, subtitleCallback, callback)
            }
            
            // Procurar iframe
            val iframePattern = Regex("""<iframe[^>]+src=["'](https?://[^"']+fembed[^"']+)["']""")
            val iframeMatch = iframePattern.find(html)
            
            if (iframeMatch != null) {
                return loadExtractor(iframeMatch.groupValues[1], finalUrl, subtitleCallback, callback)
            }
            
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}