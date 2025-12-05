package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/terror/" to "Terror"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + if (page > 1) "page/$page/" else "").document
        val items = document.select("article.TPost").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val title = a.selectFirst("h2.Title")?.text() ?: it.selectFirst(".Title")?.text() ?: return@mapNotNull null
            val link = a.attr("href")
            val poster = it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src")
            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "\( mainUrl/?s= \){query.urlEncode()}"
        val doc = app.get(url).document
        return doc.select("article.TPost").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val title = a.selectFirst("h2.Title")?.text() ?: return@mapNotNull null
            val link = a.attr("href")
            val poster = it.selectFirst("img")?.attr("data-src") ?: it.selectFirst("img")?.attr("src")
            newMovieSearchResponse(title, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.Title")?.text() ?: doc.selectFirst(".Title")?.text() ?: "Sem título"
        val poster = doc.selectFirst(".Image img")?.attr("src")?.let { fixUrl(it) }
        val plot = doc.selectFirst(".Description p")?.text()
        val tags = doc.select(".Info a[href*='/category/']").map { it.text() }

        val isTv = url.contains("/serie/")

        return if (isTv) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, url) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            val iframe = doc.selectFirst("iframe[src*='assistirseriesonline'], iframe[data-src*='assistirseriesonline']")
                ?.let { it.attr("src").ifBlank { it.attr("data-src") } } ?: ""
            newMovieLoadResponse(title, url, TvType.Movie, iframe) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val url = when {
            data.matches(Regex("^\\d+$")) -> "https://assistirseriesonline.icu/episodio/$data"
            data.startsWith("http") -> data
            else -> return false
        }

        val res = app.get(url, referer = mainUrl)
        val script = res.document.selectFirst("script:containsData(player)")?.data() ?: return false

        val videoUrl = Regex("""["'](?:file|src)["']?\s*:\s*["'](https?://[^"']+embedplay[^"']+)""").find(script)?.groupValues?.get(1)
            ?: return false

        val finalUrl = if (videoUrl.contains("embedplay.upns.pro") || videoUrl.contains("embedplay.upn.one")) {
            val id = videoUrl.substringAfterLast("/").substringBefore("?")
            "https://player.ultracine.org/watch/$id"
        } else videoUrl

        callback.invoke(
            ExtractorLink(
                source = name,
                name = "$name 4K • Tela Cheia",
                url = finalUrl,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = true
            )
        )
        return true
    }
}