package com.CineAgora

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat

class CineAgora : MainAPI() {
    override var mainUrl = "https://cineagora.net"
    override var name = "CineAgora"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    private val tmdbImageUrl = "https://image.tmdb.org/t/p"
    private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
    private val TMDB_ACCESS_TOKEN = BuildConfig.TMDB_ACCESS_TOKEN

    companion object {
        private val HOME_SECTIONS = listOf(
            "ultimos-filmes" to "Últimos Filmes",
            "ultimas-series" to "Últimas Séries"
        )
        
        private val SECTION_URLS = mapOf(
            "ultimos-filmes" to "https://cineagora.net/filmes-hd-online/",
            "ultimas-series" to "https://cineagora.net/series-online-hd-gratis/",
            "filmes-populares" to "https://cineagora.net/filmes-hd-online/filmes-populares-hd/",
            "series-populares" to "https://cineagora.net/series-online-hd-gratis/series-populares-hd/",
            "netflix" to "https://cineagora.net/netflix/",
            "paramount" to "https://cineagora.net/paramount/",
            "disney" to "https://cineagora.net/disney/",
            "apple" to "https://cineagora.net/apple/",
            "hbo" to "https://cineagora.net/hbo/",
            "acao" to "https://cineagora.net/filmes-hd-online/filmes-de-acao-hd/",
            "aventura" to "https://cineagora.net/filmes-hd-online/filmes-de-aventura-gratis/",
            "animacao" to "https://cineagora.net/filmes-hd-online/filmes-de-animacao-online/",
            "biograficos" to "https://cineagora.net/filmes-hd-online/assistir-filmes-biograficos/",
            "comedia" to "https://cineagora.net/filmes-hd-online/comedia-filmes-online/",
            "crime" to "https://cineagora.net/filmes-hd-online/crime-filmes-online/",
            "documentarios" to "https://cineagora.net/filmes-hd-online/documentarios-em-portugues/",
            "esporte" to "https://cineagora.net/filmes-hd-online/filmes-de-esporte-hd/",
            "drama" to "https://cineagora.net/filmes-hd-online/filmes-drama-online-hd/",
            "familia" to "https://cineagora.net/filmes-hd-online/filmes-familia-online/",
            "fantasia" to "https://cineagora.net/filmes-hd-online/filmes-fantasia-magia/",
            "historicos" to "https://cineagora.net/filmes-hd-online/filmes-historicos-hd/",
            "terror" to "https://cineagora.net/filmes-hd-online/filmes-terror-horror/",
            "musicais" to "https://cineagora.net/filmes-hd-online/filmes-musicais-online/",
            "misterio" to "https://cineagora.net/filmes-hd-online/filmes-misterio-suspense/",
            "romanticos" to "https://cineagora.net/filmes-hd-online/filmes-romanticos-online/",
            "suspense" to "https://cineagora.net/filmes-hd-online/filmes-suspense-hd/",
            "sci-fi" to "https://cineagora.net/filmes-hd-online/ficcao-cientifica-hd/",
            "tv" to "https://cineagora.net/filmes-hd-online/filmes-para-tv-hd/",
            "thriller" to "https://cineagora.net/filmes-hd-online/thriller-suspense-online/",
            "guerra" to "https://cineagora.net/filmes-hd-online/filmes-guerra-epicas/",
            "faroeste" to "https://cineagora.net/filmes-hd-online/filmes-faroeste-online/"
        )
    }

    override val mainPage = mainPageOf(
        *HOME_SECTIONS.map { (section, name) -> 
            "home_$section" to name 
        }.toTypedArray(),
        *SECTION_URLS.filterKeys { it !in HOME_SECTIONS.map { it.first } }
                     .map { (section, _) ->
                         "section_$section" to getSectionName(section)
                     }.toTypedArray()
    )

    private fun getSectionName(section: String): String {
        return when (section) {
            "ultimos-filmes" -> "Últimos Filmes"
            "ultimas-series" -> "Últimas Séries"
            "filmes-populares" -> "Filmes Populares"
            "series-populares" -> "Séries Populares"
            "netflix" -> "Netflix"
            "paramount" -> "Paramount+"
            "disney" -> "Disney+"
            "apple" -> "Apple TV+"
            "hbo" -> "HBO Max"
            "acao" -> "Ação"
            "aventura" -> "Aventura"
            "animacao" -> "Animação"
            "biograficos" -> "Biográficos"
            "comedia" -> "Comédia"
            "crime" -> "Crime"
            "documentarios" -> "Documentários"
            "esporte" -> "Esporte"
            "drama" -> "Drama"
            "familia" -> "Família"
            "fantasia" -> "Fantasia"
            "historicos" -> "Históricos"
            "terror" -> "Terror"
            "musicais" -> "Musicais"
            "misterio" -> "Mistério"
            "romanticos" -> "Românticos"
            "suspense" -> "Suspense"
            "sci-fi" -> "Sci-Fi"
            "tv" -> "TV"
            "thriller" -> "Thriller"
            "guerra" -> "Guerra"
            "faroeste" -> "Faroeste"
            else -> section.replace("-", " ").split(" ").joinToString(" ") { 
                it.replaceFirstChar { char -> char.uppercase() }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionId = request.data.removePrefix("home_").removePrefix("section_")
        val baseUrl = SECTION_URLS[sectionId] ?: mainUrl
        
        val url = if (page > 1) {
            val cleanUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            "$cleanUrl/page/$page/"
        } else {
            baseUrl
        }
        
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            if (page > 1) {
                return newHomePageResponse(request.name, emptyList(), false)
            } else {
                throw e
            }
        }
        
        val items = extractSectionItems(document, sectionId)
        val hasNextPage = checkForNextPage(document, page)
        
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNextPage)
    }

    private fun checkForNextPage(document: org.jsoup.nodes.Document, currentPage: Int): Boolean {
        val pagination = document.select(".pagination, .nav-links, .page-numbers, a[href*='page/']")
        
        val nextPageLinks = pagination.filter { element ->
            val href = element.attr("href")
            val text = element.text().lowercase()
            href.contains("/page/${currentPage + 1}/") || 
            text.contains("próxima") || 
            text.contains("next") ||
            element.hasClass("next") ||
            element.hasClass("next-page")
        }
        
        val pageNumbers = document.select(".page-numbers, .page-number, [class*='page']")
            .filter { it.text().matches(Regex("\\d+")) }
            .mapNotNull { it.text().toIntOrNull() }
            .sorted()
        
        return pageNumbers.any { it > currentPage } || nextPageLinks.isNotEmpty()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchUrl = mainUrl
        
        return try {
            val document = app.post(
                url = searchUrl,
                data = mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "story" to query
                ),
                referer = searchUrl,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Origin" to mainUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
            ).document
            
            extractSearchResults(document)
            
        } catch (e: Exception) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val fallbackUrl = "$mainUrl/?do=search&subaction=search&story=$encodedQuery"
                val document = app.get(fallbackUrl).document
                extractSearchResults(document)
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    private fun extractSearchResults(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val searchItems = document.select(".film-list .content .col-6.col-sm-4.col-md-3.col-lg-2 .item-relative > a.item")
        
        return if (searchItems.isNotEmpty()) {
            searchItems.mapNotNull { it.toSearchResult() }
        } else {
            document.select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
                .mapNotNull { it.toSearchResult() }
        }
    }

    private fun extractSectionItems(document: org.jsoup.nodes.Document, sectionId: String): List<SearchResponse> {
        val items = document.select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
        return items.mapNotNull { it.toSearchResult() }
    }

    private fun extractScoreAdvanced(element: Element): Pair<String?, String?> {
        val selectors = listOf(
            ".item-info-ust .rating",
            ".rating",
            ".score",
            ".item-info + div",
            ".item-footer span",
            "span:contains(★)",
            "span:contains(/10)",
            "[class*='rating']",
            "[class*='score']",
            ".item-info-ust div",
            "small",
            "b",
            "i"
        )

        for (selector in selectors) {
            val found = element.selectFirst(selector)?.text()?.trim()
            if (!found.isNullOrBlank() && isScoreLike(found)) {
                return found to selector
            }
        }

        element.parent()?.let { parent ->
            for (selector in selectors) {
                val found = parent.selectFirst(selector)?.text()?.trim()
                if (!found.isNullOrBlank() && isScoreLike(found)) {
                    return found to "parent.$selector"
                }
            }
        }

        val html = element.outerHtml()
        val scoreRegexes = listOf(
            Regex("""(\d+\.\d+|\d+)\s*(?:★|/10|pontos)"""),
            Regex("""class="[^"]*(?:rating|score)[^"]*">([^<]+)""")
        )

        for (regex in scoreRegexes) {
            val match = regex.find(html)
            if (match != null) {
                val found = match.groupValues[1].trim()
                if (isScoreLike(found)) {
                    return found to "regex"
                }
            }
        }

        return null to null
    }

    private fun isScoreLike(text: String): Boolean {
        return when {
            text.equals("N/A", ignoreCase = true) -> true
            text.matches(Regex("""^\d+(\.\d+)?$""")) -> true
            text.matches(Regex("""^\d+(\.\d+)?/10$""")) -> true
            text.contains("★") -> true
            text.contains("pontos", ignoreCase = true) -> true
            else -> false
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a")
        val href = linkElement?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        
        val titleElement = selectFirst(".item-footer .title, .title, .poster-title, h3, h4")
        val title = titleElement?.text()?.trim() ?: return null
        
        val year = selectFirst(".info span:first-child, .year, .date")?.text()?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        val cleanTitle = title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\d{4}$"), "")
            .trim()
        
        val imgElement = selectFirst("img.thumbnail, img.poster, img")
        val posterUrl = imgElement?.attr("src")?.let { fixUrl(it) }
        
        val qualityBadge = select(".item-info, .quality, .badge").firstOrNull()?.selectFirst("div:first-child, span")?.text()?.trim()
        
        val languageBadge = select(".item-info, .language, .badge").firstOrNull()?.selectFirst("div:nth-child(2), .lang")?.text()?.trim()
        
        val scoreResult = extractScoreAdvanced(this)
        val scoreText = scoreResult.first
        val score = when {
            scoreText == null || scoreText == "N/A" -> null
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) }
        }
        
        val lastEpisodeInfo = select(".item-info, .episode, .data").getOrNull(1)?.selectFirst("small, .last-ep")?.text()?.trim()
            ?: selectFirst(".data, .episode-info")?.text()?.trim()
        
        val isSerie = href.contains("/series-") || href.contains("/serie-") || href.contains("/tv-") || 
                      href.contains("/series-online") ||
                      lastEpisodeInfo?.contains(Regex("S\\d+.*E\\d+")) == true ||
                      title.contains(Regex("(?i)(temporada|episódio|season|episode)"))
        
        val quality = when {
            qualityBadge?.contains("HD", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("4K", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("FULLHD", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("TS", ignoreCase = true) == true -> SearchQuality.Cam
            else -> null
        }
        
        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
                if (quality != null) {
                    this.quality = quality
                }
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
                if (quality != null) {
                    this.quality = quality
                }
            }
        }
    }

    private fun extractBannerUrl(doc: org.jsoup.nodes.Document): String? {
        val bannerSelectors = listOf(
            "meta[property='og:image']",
            "meta[name='twitter:image']",
            "picture img",
            "picture source[media='(max-width: 768px)']",
            "picture img[alt*='assistir'][title*='Assistir']",
            "picture img[loading='lazy']",
            ".cover-img",
            ".banner-img",
            "img.banner",
            ".hero img",
            ".featured-image img",
            ".post-thumbnail img",
            ".single-featured-image img",
            "[class*='banner'] img",
            "[class*='cover'] img",
            ".movie-banner",
            ".series-banner",
            ".post-content img",
            ".entry-content img",
            ".article-content img",
            "img[title*='Assistir']",
            "img[alt*='assistir']",
            "img[alt*='online']",
            "img[title*='online']"
        )
        
        for (selector in bannerSelectors) {
            val element = doc.selectFirst(selector)
            if (element != null) {
                val url = when {
                    selector.startsWith("meta[") -> element.attr("content")
                    selector.contains("source[") -> element.attr("srcset")
                    else -> element.attr("src")
                }
                
                if (url.isNotBlank()) {
                    val fixedUrl = fixUrl(url)
                    
                    if (selector.contains("source[") && url.contains(",")) {
                        val firstUrl = url.substringBefore(",").trim()
                        if (firstUrl.isNotBlank()) {
                            return fixUrl(firstUrl)
                        }
                    }
                    
                    return fixedUrl
                }
            }
        }
        
        val pictureElements = doc.select("picture")
        for (picture in pictureElements) {
            val imgElement = picture.selectFirst("img")
            if (imgElement != null) {
                val src = imgElement.attr("src")
                if (src.isNotBlank()) {
                    return fixUrl(src)
                }
            }
            
            val sourceElement = picture.selectFirst("source")
            if (sourceElement != null) {
                val srcset = sourceElement.attr("srcset")
                if (srcset.isNotBlank()) {
                    val firstUrl = srcset.split(",").firstOrNull()?.trim()?.substringBefore(" ")?.trim()
                    if (!firstUrl.isNullOrBlank()) {
                        return fixUrl(firstUrl)
                    }
                }
            }
        }
        
        return null
    }

    private fun extractYear(doc: org.jsoup.nodes.Document): Int? {
        return doc.selectFirst(".year, .date, time")?.text()?.toIntOrNull()
            ?: Regex("(\\d{4})").find(doc.selectFirst("h1")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractGenres(doc: org.jsoup.nodes.Document): List<String>? {
        val genres = doc.select(".genres a, .genre a, .category a, a[href*='genero'], a[href*='categoria']")
            .mapNotNull { it.text().trim() }
            .filter { it.isNotBlank() }
        
        return if (genres.isNotEmpty()) genres else null
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = if (isTv) {
                "https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            } else {
                "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            }

            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val response = app.get(searchUrl, headers = headers, timeout = 10_000)
            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null

            val details = getTMDBDetails(result.id, isTv) ?: return null

            val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    val actorObj = Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                    Pair(actorObj, actor.character)
                } else null
            }

            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            val seasonsEpisodes = if (isTv) {
                getTMDBAllSeasons(result.id)
            } else {
                emptyMap()
            }

            TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) {
                    result.first_air_date?.substring(0, 4)?.toIntOrNull()
                } else {
                    result.release_date?.substring(0, 4)?.toIntOrNull()
                },
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details.overview,
                genres = details.genres?.map { it.name },
                actors = allActors,
                youtubeTrailer = youtubeTrailer,
                duration = if (!isTv) details.runtime else null,
                seasonsEpisodes = seasonsEpisodes,
                rating = details.vote_average?.takeIf { it > 0 }
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val seriesDetailsUrl = "https://api.themoviedb.org/3/tv/$seriesId?api_key=$TMDB_API_KEY&language=pt-BR"
            val seriesResponse = app.get(seriesDetailsUrl, headers = headers, timeout = 10_000)

            if (seriesResponse.code != 200) return emptyMap()

            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>() ?: return emptyMap()

            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            for (season in seriesDetails.seasons) {
                if (season.season_number > 0) {
                    val seasonNumber = season.season_number
                    val seasonUrl = "https://api.themoviedb.org/3/tv/$seriesId/season/$seasonNumber?api_key=$TMDB_API_KEY&language=pt-BR"
                    val seasonResponse = app.get(seasonUrl, headers = headers, timeout = 10_000)

                    if (seasonResponse.code == 200) {
                        val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                        seasonData?.episodes?.let { episodes ->
                            seasonsEpisodes[seasonNumber] = episodes
                        }
                    }
                }
            }

            seasonsEpisodes
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val url = if (isTv) {
                "https://api.themoviedb.org/3/tv/$id?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            } else {
                "https://api.themoviedb.org/3/movie/$id?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            }

            val response = app.get(url, headers = headers, timeout = 10_000)
            if (response.code != 200) return null
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            null
        }
    }

    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        if (videos.isNullOrEmpty()) return null

        return videos.mapNotNull { video ->
            when {
                video.site == "YouTube" && video.type == "Trailer" && video.official == true ->
                    Triple(video.key, 10, "YouTube Trailer Oficial")
                video.site == "YouTube" && video.type == "Trailer" ->
                    Triple(video.key, 9, "YouTube Trailer")
                video.site == "YouTube" && video.type == "Teaser" && video.official == true ->
                    Triple(video.key, 8, "YouTube Teaser Oficial")
                video.site == "YouTube" && video.type == "Teaser" ->
                    Triple(video.key, 7, "YouTube Teaser")
                else -> null
            }
        }
        ?.sortedByDescending { it.second }
        ?.firstOrNull()
        ?.let { (key, _, _) -> "https://www.youtube.com/watch?v=$key" }
    }

    private suspend fun extractSeriesSlugFromPage(doc: org.jsoup.nodes.Document, baseUrl: String): String? {
        val iframes = doc.select("iframe[src*='/tv/']")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            val tvPattern = Regex("""/tv/([^/?]+)""")
            val tvMatch = tvPattern.find(src)
            if (tvMatch != null) {
                return tvMatch.groupValues[1]
            }
        }
        
        val allIframes = doc.select("iframe[src*='watch.brplayer.cc']")
        for (iframe in allIframes) {
            val src = iframe.attr("src")
            
            val tvPattern = Regex("""/tv/([^/?]+)""")
            val tvMatch = tvPattern.find(src)
            if (tvMatch != null) {
                return tvMatch.groupValues[1]
            }
            
            val watchPattern = Regex("""/watch/([^/?]+)""")
            val watchMatch = watchPattern.find(src)
            if (watchMatch != null) {
                val videoSlug = watchMatch.groupValues[1]
                val seriesSlug = getSeriesFromVideoSlug(videoSlug)
                if (seriesSlug.isNotBlank()) {
                    return seriesSlug
                }
            }
        }
        
        val scripts = doc.select("script")
        for (script in scripts) {
            val scriptText = script.html()
            val tvPattern = Regex("""["'](https?://watch\.brplayer\.cc/tv/[^"']+)["']""")
            val matches = tvPattern.findAll(scriptText)
            
            for (match in matches) {
                val url = match.groupValues[1]
                val slug = url.substringAfterLast("/tv/").substringBefore("?").substringBefore("#")
                if (slug.isNotBlank()) {
                    return slug
                }
            }
        }
        
        return null
    }

    private suspend fun getSeriesFromVideoSlug(videoSlug: String): String {
        try {
            val apiUrl = "https://watch.brplayer.cc/get_series_from_video.php?videoSlug=$videoSlug"
            val response = app.get(apiUrl, timeout = 10)
            if (response.isSuccessful) {
                val seriesSlug = response.text.trim()
                if (seriesSlug.isNotBlank() && seriesSlug != "null") {
                    return seriesSlug
                }
            }
        } catch (e: Exception) {}
        return ""
    }

    private suspend fun fetchEpisodesFromApi(seriesSlug: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val apiUrl = "https://watch.brplayer.cc/fetch_series_data.php?seriesSlug=$seriesSlug"
        
        try {
            val headers = mapOf(
                "accept" to "application/json, text/javascript, */*; q=0.01",
                "accept-language" to "pt-BR",
                "referer" to "https://watch.brplayer.cc/tv/$seriesSlug",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
                "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "x-requested-with" to "XMLHttpRequest"
            )
            
            val response = app.get(apiUrl, headers = headers, timeout = 30)
            
            if (!response.isSuccessful) return episodes
            
            val jsonText = response.text
            if (jsonText.isEmpty() || jsonText == "null") return episodes
            
            val responseMap: Map<String, Any>? = AppUtils.parseJson(jsonText)
            val seasonsMap = responseMap?.get("seasons") as? Map<String, List<Map<String, Any>>> ?: return episodes
            
            seasonsMap.forEach { (seasonStr, episodeList) ->
                val seasonNum = seasonStr.toIntOrNull() ?: 1
                
                episodeList.forEachIndexed { index, epMap ->
                    val videoSlug = epMap["video_slug"] as? String ?: return@forEachIndexed
                    val epNumberStr = epMap["episode_number"] as? String
                    val epTitleRaw = epMap["episode_title"] as? String
                    
                    val epNumber = epNumberStr?.toIntOrNull() ?: (index + 1)
                    val episodeTitle = "Episódio $epNumber"
                    
                    val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                    
                    episodes.add(
                        newEpisode(episodeUrl) {
                            name = episodeTitle
                            season = seasonNum
                            episode = epNumber
                            description = "Temporada $seasonNum • Episódio $epNumber"
                        }
                    )
                }
            }
        } catch (e: Exception) {}
        
        return episodes
    }

    private suspend fun extractEpisodes(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val seriesSlug = extractSeriesSlugFromPage(doc, baseUrl)
        
        if (seriesSlug != null) {
            val apiEpisodes = fetchEpisodesFromApi(seriesSlug)
            if (apiEpisodes.isNotEmpty()) {
                return apiEpisodes.sortedWith(compareBy({ it.season }, { it.episode }))
            }
        }
        
        return extractEpisodesFromHtmlWithSeasons(doc, baseUrl)
    }
    
    private fun extractEpisodesFromHtmlWithSeasons(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val seasonSelectors = doc.select(".season-selector, .season-tabs, .temporadas, [class*='season']")
        
        if (seasonSelectors.isNotEmpty()) {
            val seasonElements = doc.select(".season-item, [data-season], .temporada")
            
            if (seasonElements.isNotEmpty()) {
                seasonElements.forEach { seasonElement ->
                    val seasonNum = extractSeasonNumber(seasonElement)
                    val seasonEpisodes = extractEpisodesFromSeasonElement(seasonElement, seasonNum)
                    episodes.addAll(seasonEpisodes)
                }
            } else {
                return extractEpisodesWithSeasonDetection(doc, baseUrl)
            }
        } else {
            return extractEpisodesFromDropdownWithSeasons(doc, baseUrl)
        }
        
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(baseUrl) {
                    name = "Episódio 1"
                    season = 1
                    episode = 1
                }
            )
        }
        
        return episodes
    }
    
    private fun extractEpisodesFromDropdownWithSeasons(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeDropdown = doc.selectFirst("#episodeDropdown, .episode-list, .episodes-container")
        
        if (episodeDropdown != null) {
            val episodeElements = episodeDropdown.select("button[data-id], a[data-id], .episode-item, [class*='episode']")
            
            var currentSeason = 1
            var episodeCounter = 1
            
            episodeElements.forEachIndexed { _, element ->
                val videoSlug = element.attr("data-id") ?: element.attr("href")?.substringAfterLast("/") ?: ""
                if (videoSlug.isBlank()) return@forEachIndexed
                
                val elementText = element.text().trim()
                val seasonFromText = detectSeasonFromText(elementText)
                
                if (seasonFromText != null) {
                    currentSeason = seasonFromText
                    episodeCounter = 1
                }
                
                val epNumberFromText = extractEpisodeNumberFromText(elementText)
                val finalEpisodeNum = epNumberFromText ?: episodeCounter
                
                val episodeTitle = if (elementText.isNotBlank() && elementText != "Episódio $finalEpisodeNum") {
                    elementText
                } else {
                    "Episódio $finalEpisodeNum"
                }
                
                val episodeUrl = if (videoSlug.contains("http")) videoSlug else "https://watch.brplayer.cc/watch/$videoSlug"
                
                episodes.add(
                    newEpisode(episodeUrl) {
                        name = episodeTitle
                        season = currentSeason
                        episode = finalEpisodeNum
                        description = "Temporada $currentSeason • Episódio $finalEpisodeNum"
                    }
                )
                
                episodeCounter++
            }
        }
        
        return if (episodes.isEmpty()) {
            extractEpisodesFromHtmlFallback(doc, baseUrl)
        } else {
            episodes
        }
    }
    
    private fun extractEpisodesWithSeasonDetection(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val allElements = doc.select("button, a, div, span").filter { element ->
            val text = element.text().trim()
            val hasEpisodePattern = text.contains(Regex("""(?i)(epis[oó]dio|episode|ep\.?|temporada|season)"""))
            val hasNumber = text.contains(Regex("""\d+"""))
            hasEpisodePattern && hasNumber
        }
        
        var currentSeason = 1
        var episodeCounter = 1
        
        allElements.forEach { element ->
            val elementText = element.text().trim()
            
            val seasonMatch = Regex("""(?i)temporada\s*(\d+)|season\s*(\d+)|S(\d+)""").find(elementText)
            if (seasonMatch != null) {
                val seasonNum = seasonMatch.groupValues[1].toIntOrNull() ?: 
                               seasonMatch.groupValues[2].toIntOrNull() ?:
                               seasonMatch.groupValues[3].toIntOrNull()
                if (seasonNum != null && seasonNum != currentSeason) {
                    currentSeason = seasonNum
                    episodeCounter = 1
                }
            }
            
            val epMatch = Regex("""(?i)epis[oó]dio\s*(\d+)|episode\s*(\d+)|E(\d+)""").find(elementText)
            val epNumber = epMatch?.groupValues?.get(1)?.toIntOrNull() ?:
                          epMatch?.groupValues?.get(2)?.toIntOrNull() ?:
                          epMatch?.groupValues?.get(3)?.toIntOrNull() ?:
                          episodeCounter
            
            val videoSlug = element.attr("data-id") ?: element.attr("href")?.substringAfterLast("/") ?: ""
            if (videoSlug.isBlank()) return@forEach
            
            val episodeUrl = if (videoSlug.contains("http")) videoSlug else "https://watch.brplayer.cc/watch/$videoSlug"
            
            episodes.add(
                newEpisode(episodeUrl) {
                    name = "Episódio $epNumber"
                    season = currentSeason
                    episode = epNumber
                    description = "Temporada $currentSeason • Episódio $epNumber"
                }
            )
            
            episodeCounter++
        }
        
        return if (episodes.isNotEmpty()) episodes else extractEpisodesFromHtmlFallback(doc, baseUrl)
    }
    
    private fun extractSeasonNumber(element: Element): Int {
        val dataSeason = element.attr("data-season").toIntOrNull()
        if (dataSeason != null) return dataSeason
        
        val text = element.text().trim()
        val patterns = listOf(
            Regex("""Temporada\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""S(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""T(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d+)\b""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 1
            }
        }
        
        return 1
    }
    
    private fun extractEpisodesFromSeasonElement(seasonElement: Element, seasonNum: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeElements = seasonElement.select(".episode-item, button[data-id], a[data-id], [class*='episode']")
        
        episodeElements.forEachIndexed { index, element ->
            val videoSlug = element.attr("data-id") ?: element.attr("href")?.substringAfterLast("/") ?: ""
            if (videoSlug.isBlank()) return@forEachIndexed
            
            val elementText = element.text().trim()
            val epNumberFromText = extractEpisodeNumberFromText(elementText)
            val finalEpisodeNum = epNumberFromText ?: (index + 1)
            
            val episodeTitle = if (elementText.isNotBlank() && elementText != "Episódio $finalEpisodeNum") {
                elementText
            } else {
                "Episódio $finalEpisodeNum"
            }
            
            val episodeUrl = if (videoSlug.contains("http")) videoSlug else "https://watch.brplayer.cc/watch/$videoSlug"
            
            episodes.add(
                newEpisode(episodeUrl) {
                    name = episodeTitle
                    season = seasonNum
                    episode = finalEpisodeNum
                    description = "Temporada $seasonNum • Episódio $finalEpisodeNum"
                }
            )
        }
        
        return episodes
    }
    
    private fun detectSeasonFromText(text: String): Int? {
        val patterns = listOf(
            Regex("""Temporada\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Season\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""S(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""T(\d+)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        
        return null
    }
    
    private fun extractEpisodesFromHtmlFallback(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val episodeDropdown = doc.selectFirst("#episodeDropdown")
        if (episodeDropdown != null) {
            val episodeButtons = episodeDropdown.select("button[data-id]")
            
            episodeButtons.forEachIndexed { index, button ->
                val episodeNum = index + 1
                val videoSlug = button.attr("data-id")
                val buttonText = button.text().trim()
                
                val epNumberFromText = extractEpisodeNumberFromText(buttonText)
                val finalEpisodeNum = epNumberFromText ?: episodeNum
                
                val episodeTitle = if (buttonText.isNotBlank() && buttonText != "Episódio $finalEpisodeNum") {
                    buttonText
                } else {
                    "Episódio $finalEpisodeNum"
                }
                
                val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                
                episodes.add(
                    newEpisode(episodeUrl) {
                        name = episodeTitle
                        season = 1
                        episode = finalEpisodeNum
                        description = "Episódio $finalEpisodeNum"
                    }
                )
            }
        }
        
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(baseUrl) {
                    name = "Episódio 1"
                    season = 1
                    episode = 1
                }
            )
        }
        
        return episodes
    }
    
    private fun extractEpisodeNumberFromText(text: String): Int? {
        val patterns = listOf(
            Regex("""Episódio\s+(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d{1,3})\b""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        
        return null
    }

    private suspend fun enrichEpisodesWithTMDBInfo(
        episodes: List<Episode>,
        tmdbInfo: TMDBInfo?
    ): List<Episode> {
        if (tmdbInfo == null || tmdbInfo.seasonsEpisodes.isEmpty()) return episodes
        
        return episodes.map { originalEpisode ->
            val season = originalEpisode.season ?: 1
            val episodeNum = originalEpisode.episode ?: 1
            
            val tmdbEpisode = findTMDBEpisode(tmdbInfo, season, episodeNum)
            
            if (tmdbEpisode != null) {
                val descriptionWithDuration = buildDescriptionWithDuration(
                    tmdbEpisode.overview,
                    tmdbEpisode.runtime
                )
                
                newEpisode(originalEpisode.data) {
                    this.name = tmdbEpisode.name
                    this.season = season
                    this.episode = episodeNum
                    this.posterUrl = tmdbEpisode.still_path?.let { "$tmdbImageUrl/w300$it" }
                    this.description = descriptionWithDuration
                    
                    tmdbEpisode.air_date?.let { airDate ->
                        try {
                            val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                            val date = dateFormatter.parse(airDate)
                            this.date = date.time
                        } catch (e: Exception) {}
                    }
                }
            } else {
                originalEpisode
            }
        }
    }
    
    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? {
        if (tmdbInfo == null) return null

        val episodes = tmdbInfo.seasonsEpisodes[season]
        if (episodes == null) {
            for ((_, seasonEpisodes) in tmdbInfo.seasonsEpisodes) {
                val found = seasonEpisodes.find { it.episode_number == episode }
                if (found != null) return found
            }
            return null
        }

        return episodes.find { it.episode_number == episode }
    }

    private fun buildDescriptionWithDuration(overview: String?, runtime: Int?): String? {
        return when {
            overview != null && runtime != null && runtime > 0 -> "$overview\n\nDuração: $runtime min"
            overview != null -> overview
            runtime != null && runtime > 0 -> "Duração: $runtime min"
            else -> null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        
        val bannerUrl = extractBannerUrl(doc)
        val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: doc.selectFirst("#info--box .cover-img")?.attr("src")?.let { fixUrl(it) }
            ?: bannerUrl
        
        val title = doc.selectFirst("h1.title, h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        val episodes = extractEpisodes(doc, url)
        
        if (episodes.isEmpty()) return null
        
        val isSerie = url.contains("/series-") || url.contains("/serie-") || url.contains("/tv-") || 
                     url.contains("/series-online") ||
                     doc.select(".player-controls, #episodeDropdown, .seasons").isNotEmpty() ||
                     episodes.size > 1
        
        val yearFromSite = extractYear(doc)
        val plotFromSite = doc.selectFirst(".info-description, .description, .sinopse, .plot")?.text()?.trim()
        val genresFromSite = extractGenres(doc)
        
        val tmdbInfo = searchOnTMDB(cleanTitle, yearFromSite, isSerie)
        
        val enrichedEpisodes = if (isSerie && tmdbInfo != null) {
            enrichEpisodesWithTMDBInfo(episodes, tmdbInfo)
        } else {
            episodes
        }
        
        val recommendations = extractRecommendationsFromSite(doc)
        
        val playerUrl = if (!isSerie) {
            findPlayerUrl(doc) ?: url
        } else {
            url
        }
        
        return if (isSerie) {
            createSeriesLoadResponse(tmdbInfo, url, enrichedEpisodes, recommendations, plotFromSite, genresFromSite, bannerUrl, posterUrl, yearFromSite)
        } else {
            createMovieLoadResponse(tmdbInfo, playerUrl, recommendations, plotFromSite, genresFromSite, bannerUrl, posterUrl, yearFromSite)
        }
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst("iframe[src*='watch.brplayer.cc']")
        if (iframe != null) {
            val src = iframe.attr("src")
            val watchPattern = Regex("""/watch/([^/?]+)""")
            val watchMatch = watchPattern.find(src)
            if (watchMatch != null) {
                val videoSlug = watchMatch.groupValues[1]
                return "https://watch.brplayer.cc/watch/$videoSlug"
            }
        }
        return null
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
            .mapNotNull { it.toSearchResult() }
            .take(10)
    }

    private suspend fun createSeriesLoadResponse(
        tmdbInfo: TMDBInfo?,
        url: String,
        episodes: List<Episode>,
        siteRecommendations: List<SearchResponse>,
        plotFromSite: String?,
        genresFromSite: List<String>?,
        bannerUrlFromSite: String?,
        posterUrlFromSite: String?,
        yearFromSite: Int?
    ): LoadResponse {
        val title = tmdbInfo?.title ?: app.get(url).document.selectFirst("h1.title, h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
        val year = tmdbInfo?.year ?: yearFromSite
        val plot = tmdbInfo?.overview ?: plotFromSite
        val posterUrl = tmdbInfo?.posterUrl ?: posterUrlFromSite
        val backdropUrl = tmdbInfo?.backdropUrl ?: bannerUrlFromSite
        val genres = tmdbInfo?.genres ?: genresFromSite
        val rating = tmdbInfo?.rating?.let { Score.from10(it) }
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backdropUrl
            this.year = year
            this.plot = plot
            this.tags = genres
            this.score = rating
            this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            
            tmdbInfo?.actors?.let { actors ->
                addActors(actors)
            }
            
            tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                addTrailer(trailerUrl)
            }
        }
    }

    private suspend fun createMovieLoadResponse(
        tmdbInfo: TMDBInfo?,
        playerUrl: String,
        siteRecommendations: List<SearchResponse>,
        plotFromSite: String?,
        genresFromSite: List<String>?,
        bannerUrlFromSite: String?,
        posterUrlFromSite: String?,
        yearFromSite: Int?
    ): LoadResponse {
        val doc = app.get(playerUrl).document
        val title = tmdbInfo?.title ?: doc.selectFirst("h1.title, h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
        val year = tmdbInfo?.year ?: yearFromSite
        val plot = tmdbInfo?.overview ?: plotFromSite
        val posterUrl = tmdbInfo?.posterUrl ?: posterUrlFromSite
        val backdropUrl = tmdbInfo?.backdropUrl ?: bannerUrlFromSite
        val genres = tmdbInfo?.genres ?: genresFromSite
        val duration = tmdbInfo?.duration
        val rating = tmdbInfo?.rating?.let { Score.from10(it) }
        
        return newMovieLoadResponse(title, playerUrl, TvType.Movie, playerUrl) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backdropUrl
            this.year = year
            this.plot = plot
            this.tags = genres
            this.duration = duration
            this.score = rating
            this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            
            tmdbInfo?.actors?.let { actors ->
                addActors(actors)
            }
            
            tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                addTrailer(trailerUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("youtube.com") || data.contains("youtu.be")) {
            return false
        }
        
        return CineAgoraExtractor.extractVideoLinks(data, name, callback)
    }

    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val actors: List<Pair<Actor, String?>>?,
        val youtubeTrailer: String?,
        val duration: Int?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>> = emptyMap(),
        val rating: Double? = null
    )

    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )

    private data class TMDBSeasonInfo(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
    )

    private data class TMDBSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TMDBEpisode>,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?,
        @JsonProperty("vote_average") val vote_average: Double?
    )

    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profile_path: String?
    )

    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )
}
