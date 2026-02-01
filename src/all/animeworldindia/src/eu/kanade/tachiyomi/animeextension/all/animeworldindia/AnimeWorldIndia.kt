package eu.kanade.tachiyomi.animeextension.all.animeworldindia

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import extensions.utils.getPreferencesLazy
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeWorldIndia(
    final override val lang: String,
    private val language: String,
) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "WatchAnimeWorld"
    override val baseUrl = "https://watchanimeworld.net"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) =
        GET("$baseUrl/category/anime/page/$page/")

    override fun popularAnimeSelector() = "article"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val a = element.selectFirst("h2 a, h3 a, a[href]")!!
        setUrlWithoutDomain(a.attr("href"))
        title = a.text().trim()
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    override fun popularAnimeNextPageSelector() = "a.next, a.page-numbers.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) =
        GET("$baseUrl/?paged=$page")

    override fun latestUpdatesSelector() = "article"

    override fun latestUpdatesFromElement(element: Element) =
        popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "a.next, a.page-numbers.next"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/?s=$query&paged=$page")
    }

    override fun searchAnimeSelector() = "article"

    override fun searchAnimeFromElement(element: Element) =
        popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = "a.next, a.page-numbers.next"

    override fun getFilterList() = AnimeFilterList()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("h1, h2")?.text()?.trim() ?: "Unknown"

        thumbnail_url =
            document.selectFirst("meta[property=og:image]")?.attr("abs:content")
                ?: document.selectFirst("img.wp-post-image, img")?.attr("abs:src")

        description = document.selectFirst(".entry-content, div.entry-content")?.text()?.trim()
        genre = document.select("a[rel=tag]").joinToString { it.text() }
        status = SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "a[href*='/episode/']"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val url = element.attr("href")
        setUrlWithoutDomain(url)

        val text = element.text().trim()
        name = if (text.isNotBlank()) text else url.substringAfterLast("/").ifBlank { "Episode" }

        episode_number = 0F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val iframeUrl = document.selectFirst("iframe")?.attr("abs:src")
            ?: throw Exception("No video iframe found")

        // If iframe is already direct video
        if (iframeUrl.contains(".m3u8") || iframeUrl.contains(".mp4")) {
            return listOf(Video(iframeUrl, "Direct", iframeUrl))
        }

        // Open iframe page and find direct link
        val iframeDoc = client.newCall(GET(iframeUrl, headers)).execute().asJsoup()

        val m3u8 = iframeDoc.selectFirst("source[src$=.m3u8]")?.attr("abs:src")
            ?: iframeDoc.selectFirst("a[href$=.m3u8]")?.attr("abs:href")

        val mp4 = iframeDoc.selectFirst("source[src$=.mp4]")?.attr("abs:src")
            ?: iframeDoc.selectFirst("a[href$=.mp4]")?.attr("abs:href")

        val finalUrl = m3u8 ?: mp4 ?: throw Exception("No direct stream link found")

        return listOf(Video(finalUrl, "Stream", finalUrl))
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareBy { it.quality.contains(quality) }).reversed()
    }

    // ============================ Preferences =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360", "240")
    }
}
