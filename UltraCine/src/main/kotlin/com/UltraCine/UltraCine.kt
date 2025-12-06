package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import java.net.URLEncoder

class UltraCine : MainAPI() {
    // ... (Métodos getMainPage, toSearchResult, search permanecem inalterados) ...

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = doc.selectFirst("div.bghd img, img.TPostBg")
            ?.attr("src")?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        val year = doc.selectFirst("span.year")?.text()
            ?.replace(Regex("\\D"), "")?.toIntOrNull()

        val durationText = doc.selectFirst("span.duration")?.text().orEmpty()
        val duration = parseDuration(durationText)

        // 8.5 → 8500
        val ratingInt = doc.selectFirst("div.vote span.num, .rating span")?.text()
            ?.toDoubleOrNull()?.times(1000)?.toInt()

        val plot = doc.selectFirst("div.description p, .sinopse")?.text()
        val tags = doc.select("span.genres a, .category a").map { it.text() }

        val actors = doc.select("ul.cast-lst a").mapNotNull {
            val name = it.text().trim()
            val img = it.selectFirst("img")?.attr("src")
            if (name.isNotBlank()) Actor(name, img) else null
        }

        val trailer = doc.selectFirst("div.video iframe, iframe[src*=youtube]")?.attr("src")

        // Extração do link do player do atributo data-source do botão
        val playerLinkFromButton = doc.selectFirst("div#players button[data-source]")
            ?.attr("data-source")?.takeIf { it.isNotBlank() }

        // Detecção de séries
        val isTvSeries = url.contains("/serie/") || doc.select("div.seasons").isNotEmpty()

        // Usa 'return if' para retornar o tipo correto (Série ou Filme)
        return if (isTvSeries) { 
            val episodes = mutableListOf<Episode>()

            // 1. LÓGICA DE EXTRAÇÃO DE EPISÓDIOS
            if (playerLinkFromButton != null) {
                try {
                    val iframeDoc = app.get(playerLinkFromButton).document 
                    iframeDoc.select("li[data-episode-id]").forEach { ep ->
                        val epId = ep.attr("data-episode-id")
                        val name = ep.text().trim()
                        val season = ep.parent()?.attr("data-season-number")?.toIntOrNull()
                        val episodeNum = name.substringBefore(" - ").toIntOrNull() ?: 1

                        if (epId.isNotBlank()) {
                            episodes += newEpisode(epId) {
                                this.name = name.substringAfter(" - ").ifBlank { "Episódio $episodeNum" }
                                this.season = season
                                this.episode = episodeNum
                            }
                        }
                    }
                } catch (_: Exception) {}

            } else {
                // 2. FALLBACK: PROCURA A LISTA DE EPISÓDIOS NA PÁGINA PRINCIPAL (CORREÇÃO da referência episodeUrl)
                doc.select("div.seasons ul li a[href*='/episodio/']").forEach { epLink ->
                    val href = epLink.attr("href") // Link completo (DATA)
                    val epTitle = epLink.text().trim()

                    if (href.isNotBlank()) {
                         episodes += newEpisode(href) { // PASSA O HREF CORRIGIDO AQUI
                            this.name = epTitle
                        }
                    }
                }
            }

            // Retorno da SÉRIE (CORRIGE Score)
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = ratingInt?.let { Score(it, null) } // CORRIGIDO
                addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        } else {
            // FLUXO DE FILMES (CORRIGE Score)
            newMovieLoadResponse(title, url, TvType.Movie, playerLinkFromButton ?: url) { 
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                this.score = ratingInt?.let { Score(it, null) } // CORRIGIDO
                addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        }
    } 
    
    // ... (Método loadLinks permanece inalterado) ...
}

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val link = if (data.matches(Regex("^\\d+$"))) {
            // Este regex foi usado para extrair o ID numérico do episódio, agora precisa ser o link completo.
            // Se o ID for numérico, monta o link da página do episódio.
            "https://assistirseriesonline.icu/episodio/$data/"
        } else data 

        // 1. Trata URLs de Episódio (necessita de encadeamento)
        if (link.contains("assistirseriesonline.icu") && link.contains("episodio")) {
            try {
                val doc = app.get(link, referer = mainUrl).document
                doc.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank() && src.startsWith("http")) {
                        loadExtractor(src, link, subtitleCallback, callback)
                    }
                }
            } catch (_: Exception) {}
            
            return true 
        }

        // 2. Tenta processar o link 'link' (que é o data-source) diretamente como um extrator.
        if (!link.startsWith(mainUrl)) {
             loadExtractor(link, data, subtitleCallback, callback)
        }

        // 3. Retorno final obrigatório 
        return true
    }

    private fun parseDuration(text: String): Int? {
        if (text.isBlank()) return null
        val h = Regex("(\\d+)h").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("(\\d+)m").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (h > 0 || m > 0) h * 60 + m else null
    }
}
