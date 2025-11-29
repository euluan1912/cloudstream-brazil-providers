package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.util.concurrent.TimeUnit // Necessário para o timeout em app.get()

class SuperFlix : MainAPI() {
    // A URL PRINCIPAL NÃO É MAIS DECLARADA AQUI. Ela será resolvida dinamicamente no 'init'.
    override lateinit var mainUrl: String // Deve ser 'lateinit var' pois será inicializada depois
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    init {
        // Inicializa a URL com o domínio mais recente encontrado via Google Search
        mainUrl = getWorkingDomain()
    }
    
    // --- FUNÇÃO DE BUSCA DE DOMÍNIO (Autônoma) ---
    private fun getWorkingDomain(): String {
        // Mantenha o último domínio conhecido como fallback seguro (mude para o ".hub" se necessário)
        val fallbackDomain = "https://superflix.lat" 
        
        try {
            // A query que deve sempre retornar o domínio mais recente
            val searchQuery = "SuperFlix assistir filmes"
            
            // URL de pesquisa do Google
            val searchUrl = "https://www.google.com/search?q=$searchQuery"
            
            // Faz a requisição à página de resultados do Google com timeout de 5 segundos
            val searchPage = app.get(searchUrl, timeout = 5, timeUnit = TimeUnit.SECONDS)
            
            // Procura pelo primeiro link que contenha "superflix" no link (href) e seja uma URL completa
            val linkElement = searchPage.document.select("a").firstOrNull { 
                it.attr("href").contains("superflix") && it.attr("href").startsWith("http")
            }
            
            val fullUrl = linkElement?.attr("href")

            if (fullUrl != null) {
                // Limpa a URL para pegar apenas o domínio base
                val domainBase = fullUrl
                    .substringAfter("://") 
                    .substringBefore("/")
                    .substringBefore("?")

                // Retorna a URL limpa com o protocolo HTTPS.
                return "https://$domainBase"
            }
            
        } catch (e: Exception) {
            // Se a busca falhar (ex: sem internet), usa o fallback
            println("Erro na busca dinâmica de domínio para SuperFlix: ${e.message}")
        }
        
        // Se a busca falhar, retorna o domínio de fallback.
        return fallbackDomain 
    }
    
    // As páginas principais usam a mainUrl resolvida acima
    override val mainPage = mainPageOf(
        "$mainUrl/filmes/page/" to "Filmes",
        "$mainUrl/series/page/" to "Séries"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document
        val items = doc.select("article.post").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    private fun org.jsoup.nodes.Element.toSearchResponse(): SearchResponse? {
        val title = selectFirst("h2")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src")
        return if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // A busca usa a mainUrl resolvida dinamicamente
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article.post").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text() ?: ""
        val poster = doc.selectFirst(".poster img")?.attr("src")
        val plot = doc.selectFirst(".sinopse")?.text()
        val year = doc.selectFirst(".year")?.text()?.toIntOrNull()

        return if (url.contains("/series/")) {
            val episodes = doc.select(".episodios .episodio").mapNotNull { ep ->
                newEpisode(ep.attr("href")) {
                    name = ep.selectFirst(".titulo")?.text() ?: "Episódio"
                    season = ep.attr("data-season")?.toIntOrNull()
                    episode = ep.attr("data-episode")?.toIntOrNull()
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("iframe").mapNotNull { it.attr("src") }.forEach {
            loadExtractor(it, data, subtitleCallback, callback)
        }

        doc.select("source[src]").forEach {
            val src = it.attr("src")
            callback(ExtractorLink(
                source = name,
                name = name,
                url = src,
                referer = mainUrl, // Garante que o referer usa a mainUrl dinâmica
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            ))
        }

        doc.select("track[kind=subtitles]").forEach {
            val lang = it.attr("label").ifBlank { "Português" }
            val url = it.attr("src")
            if (url.isNotBlank()) {
                subtitleCallback(SubtitleFile(lang, url))
            }
        }

        return true
    }
}