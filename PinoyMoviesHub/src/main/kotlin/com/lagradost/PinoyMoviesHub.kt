package com.lagradost

import android.os.Build
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.select.Elements
import java.time.LocalDateTime

class PinoyMoviesHub : MainAPI() {
    //private val TAG = "Dev"
    override var name = "Pinoy Movies Hub"
    override var mainUrl = "https://pinoymovieshub.ph"
    override var lang = "tl"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    override val hasDownloadSupport = true
    override val hasMainPage = true
    override val hasQuickSearch = false

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(mainUrl).document
        val rows = listOfNotNull(
            Pair("Suggestion", "div.items.featured"),
            Pair("Pinoy Movies and TV", "div.items.full"),
            //Pair("Pinoy Teleserye and TV Series", "tvload"),
            Pair("Action", "div#genre_action"),
            Pair("Comedy", "div#genre_comedy"),
            Pair("Romance", "div#genre_romance"),
            Pair("Horror", "div#genre_horror"),
            Pair("Drama", "div#genre_drama"),
            if (settingsForProvider.enableAdult) Pair("Rated-R 18+", "genre_rated-r") else null
        )
        //Log.i(TAG, "Parsing page..")
        val maindoc = doc.selectFirst("div.module")
            ?.select("div.content.full_width_layout.full")

        val all = rows.mapNotNull { pair ->
            // Fetch row title
            val title = pair.first
            // Fetch list of items and map
            //Log.i(TAG, "Title => $title")
            val results = maindoc?.select(pair.second)?.select("article").getResults(this.name)
            if (results.isEmpty()) {
                return@mapNotNull null
            }
            HomePageList(
                name = title,
                list = results,
                isHorizontalImages = false
            )
        }
        return HomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "${mainUrl}/?s=${query}"
        return app.get(searchUrl).document
            .selectFirst("div#archive-content")
            ?.select("article")
            .getResults(this.name)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val body = doc.getElementsByTag("body").firstOrNull()
        val sheader = body?.selectFirst("div.sheader")

        //Log.i(TAG, "Result => (url) ${url}")
        val poster = sheader?.selectFirst("div.poster > img")
            ?.attr("src")

        val title = sheader
            ?.selectFirst("div.data > h1")
            ?.text() ?: ""
        val descript = body?.selectFirst("div#info div.wp-content")?.text()
        val year = body?.selectFirst("span.date")?.text()?.trim()?.takeLast(4)?.toIntOrNull()

        //Parse episodes
        val episodeList = body?.selectFirst("div#episodes")
            ?.select("li")
            ?.mapNotNull {
                var epCount: Int? = null
                var seasCount: Int? = null
                val divEp = it?.selectFirst("div.episodiotitle") ?: return@mapNotNull null
                val firstA = divEp.selectFirst("a")

                it.selectFirst("div.numerando")?.text()
                    ?.split("-")?.mapNotNull { seasonEps ->
                        seasonEps.trim().toIntOrNull()
                    }?.let { divEpSeason ->
                        if (divEpSeason.isNotEmpty()) {
                            if (divEpSeason.size > 1) {
                                epCount = divEpSeason[0]
                                seasCount = divEpSeason[1]
                            } else {
                                epCount = divEpSeason[0]
                            }
                        }
                    }

                val eplink = firstA?.attr("href") ?: return@mapNotNull null
                val imageEl = it.selectFirst("img")
                val epPoster = imageEl?.attr("src") ?: imageEl?.attr("data-src")
                val date = it.selectFirst("span.date")?.text()

                val ep = Episode(
                    name = firstA.text(),
                    data = eplink,
                    posterUrl = epPoster,
                    episode = epCount,
                    season = seasCount,
                )
                ep.addDate(parseDateFromString(date))
                ep
        } ?: listOf()

        val dataUrl = doc.selectFirst("link[rel='shortlink']")
            ?.attr("href")
            ?.substringAfter("?p=") ?: ""
        //Log.i(TAG, "Result => (dataUrl) ${dataUrl}")

        if (episodeList.isNotEmpty()) {
            return TvSeriesLoadResponse(
                name = title,
                url = url,
                apiName = this.name,
                type = TvType.TvSeries,
                posterUrl = poster,
                year = year,
                plot = descript,
                episodes = episodeList
            )
        }

        //Log.i(TAG, "Result => (id) ${id}")
        return MovieLoadResponse(
            name = title,
            url = url,
            dataUrl = dataUrl,
            apiName = this.name,
            type = TvType.Movie,
            posterUrl = poster,
            year = year,
            plot = descript,
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        //Log.i(TAG, "Loading ajax request..")
        val requestLink = "${mainUrl}/wp-admin/admin-ajax.php"
        val action = "doo_player_ajax"
        val nume = "1"
        val type = "movie"
        val doc = app.post(
            url = requestLink,
            referer = mainUrl,
            headers = mapOf(
                Pair("User-Agent", USER_AGENT),
                Pair("Sec-Fetch-Mode", "cors")
            ),
            data = mapOf(
                Pair("action", action),
                Pair("post", data),
                Pair("nume", nume),
                Pair("type", type)
            )
        )
        //Log.i(TAG, "Response (${doc.code}) => ${doc.text}")
        AppUtils.tryParseJson<Response?>(doc.text)?.let {
            val streamLink = it.embed_url ?: ""
            //Log.i(TAG, "Response (streamLink) => ${streamLink}")
            if (streamLink.isNotBlank()) {
                loadExtractor(
                    url = streamLink,
                    referer = mainUrl,
                    callback = callback,
                    subtitleCallback = subtitleCallback
                )
            }
        }
        return true
    }

    private fun Elements?.getResults(apiName: String): List<SearchResponse> {
        return this?.mapNotNull {
            val divPoster = it.selectFirst("div.poster")
            val divData = it.selectFirst("div.data")

            val firstA = divData?.selectFirst("a")
            val link = fixUrlNull(firstA?.attr("href")) ?: return@mapNotNull null
            val qualString = divPoster?.select("span.quality")?.text()?.trim() ?: ""
            val qual = getQualityFromString(qualString)
            var tvtype = if (qualString.equals("TV")) { TvType.TvSeries } else { TvType.Movie }
            if (link.replace("$mainUrl/", "").startsWith("tvshow")) {
                tvtype = TvType.TvSeries
            }

            val name = divData?.selectFirst("a")?.text() ?: ""
            val year = divData?.selectFirst("span")?.text()
                ?.trim()?.takeLast(4)?.toIntOrNull()

            val imageDiv = divPoster?.selectFirst("img")
            var image = imageDiv?.attr("src")
            if (image.isNullOrBlank()) {
                image = imageDiv?.attr("data-src")
            }

            //Log.i(apiName, "Added => $name / $link")
            if (tvtype == TvType.TvSeries) {
                TvSeriesSearchResponse(
                    name = name,
                    url = link,
                    apiName = apiName,
                    type = tvtype,
                    posterUrl = image,
                    year = year,
                    quality = qual,
                )
            } else {
                MovieSearchResponse(
                    name = name,
                    url = link,
                    apiName = apiName,
                    type = tvtype,
                    posterUrl = image,
                    year = year,
                    quality = qual,
                )
            }
        } ?: listOf()
    }

    private fun parseDateFromString(text: String?): String? {
        if (text.isNullOrBlank()) {
            return null
        }
        var day = ""
        var month = ""
        var year = ""
        val dateSplit = text.split(".")
        if (dateSplit.isNotEmpty()) {
            if (dateSplit.size > 1) {
                val yearday = dateSplit[1].trim()
                year = yearday.takeLast(4)
                day = yearday.trim().trim(',')

                month = with (dateSplit[0].lowercase()) {
                    when {
                        startsWith("jan") -> "01"
                        startsWith("feb") -> "02"
                        startsWith("mar") -> "03"
                        startsWith("apr") -> "04"
                        startsWith("may") -> "05"
                        startsWith("jun") -> "06"
                        startsWith("jul") -> "07"
                        startsWith("aug") -> "08"
                        startsWith("sep") -> "09"
                        startsWith("oct") -> "10"
                        startsWith("nov") -> "11"
                        startsWith("dec") -> "12"
                        else -> ""
                    }
                }
            } else {
                year = dateSplit[0].trim().takeLast(4)
            }
        }
        if (day.isBlank()) {
            day = "01"
        }
        if (month.isBlank()) {
            month = "01"
        }
        if (year.isBlank()) {
            year = if (Build.VERSION.SDK_INT >= 26) {
                LocalDateTime.now().year.toString()
            } else {
                "0001"
            }
        }
        return "$year-$month-$day"
    }

    private data class Response(
        @JsonProperty("embed_url") val embed_url: String?
    )
}