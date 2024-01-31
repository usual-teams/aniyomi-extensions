package eu.kanade.tachiyomi.animeextension.id.nekopoi

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Nekopoi : ConfigurableAnimeSource, AnimeHttpSource() {
    override val name: String = "Nekopoi"
    override val baseUrl: String = "https://nekopoi.care"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val playlistExtractor by lazy {
        PlaylistUtils(client, headers)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/page/$page/")

    override fun popularAnimeParse(response: Response): AnimesPage =
        getAnimeParse(response.asJsoup())

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/page/$page/")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        getAnimeParse(response.asJsoup())

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return when {
            query.startsWith(INTENT_PREFIX) -> {
                val trimmedQuery = query
                    .removePrefix(INTENT_PREFIX)
                    .replace('-', '+')

                GET("$baseUrl/search/$trimmedQuery", headers)
            }
            else -> {
                GET("$baseUrl/search/$query/page/$page/", headers)
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return getAnimeSearchParse(response.asJsoup())
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Genre filters ignored with text search!!"),
    )

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.use { it.asJsoup() }
        val anime = SAnime.create()

        doc.selectFirst("div.eropost > div.eroinfo > h1")
            ?.text()?.trim()?.let { text -> anime.title = text }

        doc.select("div.konten > p").forEach {
            val fullText = it.text()

            if (fullText.lowercase().contains("artis")) {
                fullText.split(":").getOrNull(1)
                    ?.trim()?.let { text -> anime.artist = text }
            } else if (fullText.lowercase().contains("genre")) {
                fullText.split(":").getOrNull(1)
                    ?.trim()?.let { text -> anime.genre = text }
            } else if (fullText.lowercase().contains("sinopsis")) {
                it.nextElementSibling()?.text()
                    ?.trim()?.let { text -> anime.description = text }
            }
        }

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        throw UnsupportedOperationException()
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return listOf(
            SEpisode.create().apply {
                url = anime.url
                name = "Episode"
            },
        )
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.use { it.asJsoup() }

        return document.select("#show-stream > div.openstream > iframe")
            .parallelCatchingFlatMapBlocking {
                getVideosFromEmbed(it.attr("src"))
            }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareByDescending { it.quality.contains(quality) })
    }

//    private fun String?.toDate(): Long {
//        return runCatching { DATE_FORMATTER.parse(this?.trim() ?: "")?.time }
//            .getOrNull() ?: 0L
//    }

    private fun getAnimeParse(document: Document): AnimesPage {
        val animes = document.select("#boxid > div.eropost").map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.selectFirst("div.eroinfo > h2 > a")!!.attr("href"))
                title = it.selectFirst("div.eroinfo > h2 > a")!!.text()
                thumbnail_url = it.selectFirst("div.eroimg > div > img")!!.attr("src")
            }
        }
        val hasNextPage = try {
            val pagination = document.selectFirst("div.nav-links")!!
            val totalPage = pagination.selectFirst("a:nth-child(5)")!!.text()
            val currentPage = pagination.selectFirst("span.page-numbers.current")!!.text()
            currentPage.toInt() < totalPage.toInt()
        } catch (_: Exception) {
            false
        }
        return AnimesPage(animes, hasNextPage)
    }

    private fun getAnimeSearchParse(document: Document): AnimesPage {
        val animes = document.select("div.result > ul > li").map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.selectFirst("div.top > h2 > a")!!.attr("href"))
                title = it.selectFirst("div.top > h2 > a")!!.text()
                thumbnail_url = it.selectFirst("div.limitnjg")!!.attr("src")
            }
        }
        val hasNextPage = try {
            val pagination = document.selectFirst("div.nav-links")!!
            val totalPage = pagination.selectFirst("a:nth-child(5)")!!.text()
            val currentPage = pagination.selectFirst("span.page-numbers.current")!!.text()
            currentPage.toInt() < totalPage.toInt()
        } catch (_: Exception) {
            false
        }
        return AnimesPage(animes, hasNextPage)
    }

    private fun getVideosFromEmbed(link: String): List<Video> {
        return when {
            "streampoi" in link || "streamruby" in link -> {
                val url = Uri.parse(link)
                val postLink = url.scheme + "://" + url.host + "/dl"
                val form = FormBody.Builder().apply {
                    add("op", "embed")
                    add("file_code", url.pathSegments.last())
                    add("auto", "1")
                    add("referer", "")
                }.build()

                client.newCall(POST(postLink, body = form)).execute().use {
                    if (!it.isSuccessful) return emptyList()

                    val document = it.use { resp -> resp.asJsoup() }

                    val playlistData = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")
                        ?.data()

                    val playlists = playlistData
                        ?.let(JsUnpacker::unpack)
                        ?: return emptyList()

                    val playlistUrl = JsUnpacker.URL_REGEX
                        .find(playlists.first())?.value ?: return emptyList()

                    Log.d(name, "getVideosFromEmbed: $playlistUrl")

                    return playlistExtractor.extractFromHls(playlistUrl)
                }
            }

            else -> emptyList()
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            summary = "%s"
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    companion object {
        const val INTENT_PREFIX = "nekopoi:"

//        private val DATE_FORMATTER by lazy {
//            SimpleDateFormat("d MMMM yyyy", Locale("id", "ID"))
//        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
