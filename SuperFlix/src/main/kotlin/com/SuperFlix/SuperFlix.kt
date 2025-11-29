package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newSubtitleFile
import org.jsoup.nodes.Element

class SuperFlixProvider : MainAPI() {
    override var mainUrl = "https://superflix.com.br"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override val lang = listOf("pt")
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmes/page/" to "Filmes",
        "$mainUrl/series/page/" to "Séries"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("article.post").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = selectFirst("h2")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")
        val isSeries = href.contains("/series/")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.post").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text() ?: ""
        val poster = document.selectFirst(".poster img")?.attr("src")
        val description = document.selectFirst(".sinopse")?.text()
        val year = document.selectFirst(".year")?.text()?.toIntOrNull()
        val isSeries = url.contains("/series/")

        return if (isSeries) {
            val episodes = document.select(".episodios .episodio").mapNotNull { ep ->
                val epHref = ep.attr("href")
                val epName = ep.selectFirst(".titulo")?.text() ?: "Episódio"
                val season = ep.attr("data-season")?.toIntOrNull() ?: 1
                val episode = ep.attr("data-episode")?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = season
                    this.episode = episode
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) loadExtractor(src, data, subtitleCallback, callback)
        }

        document.select("source[src]").forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = src,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8
                    )
                )
            }
        }

        document.select("track[kind=subtitles]").forEach { track ->
            val subUrl = track.attr("src")
            val label = track.attr("label").ifBlank { "Português" }
            if (subUrl.isNotBlank()) {
                subtitleCallback.invoke(
                    newSubtitleFile {
                        lang = label
                        url = subUrl
                    }
                )
            }
        }

        return true
    }
}