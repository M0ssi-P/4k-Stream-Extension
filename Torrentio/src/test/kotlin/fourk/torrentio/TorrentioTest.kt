package fourk.torrentio

import fourk.plugin.TitleQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TorrentioTest {
    private fun q(type: String, imdb: String? = null, season: Int? = null, episode: Int? = null, kitsu: Int? = null) =
        TitleQuery(type = type, title = "x", year = null, alt = null, tmdb = null, imdb = imdb, anilist = null,
            kitsu = kitsu, mal = null, season = season, episode = episode, lang = null)

    @Test fun paths() {
        assertEquals("movie/tt1375666", streamPath(q("movie", imdb = "tt1375666")))
        assertEquals("movie/tt1375666", streamPath(q("movie", imdb = "1375666")))
        assertEquals("series/tt12343534:1:1", streamPath(q("series", imdb = "tt12343534", season = 1, episode = 1)))
        assertEquals("series/kitsu:42323:5", streamPath(q("anime", kitsu = 42323, episode = 5)))
        assertNull(streamPath(q("series", imdb = "tt12343534")))
        assertNull(streamPath(q("movie")))
    }

    // a real result from /stream/series/tt12343534:1:1.json (trackers trimmed)
    @Test fun parsesAStream() {
        val s = toSource(mapOf(
            "name" to "Torrentio\n4k",
            "title" to "[Dubs-Empire] Jujutsu Kaisen (2020) S01 v3 [2160p]\nJujutsu Kaisen (2020) S01E01.4k.h264.mkv\n👤 58 💾 1.26 GB ⚙️ NyaaSi",
            "infoHash" to "06c9a7da98c155a32bf2208ab94750a31f3a2a2c",
            "fileIdx" to 19.0, // JSON numbers may arrive as Double
            "behaviorHints" to mapOf("filename" to "Jujutsu Kaisen (2020) S01E01.4k.h264.mkv"),
        ))!!
        assertEquals("[Dubs-Empire] Jujutsu Kaisen (2020) S01 v3 [2160p]", s.name)
        assertEquals("2160p", s.quality)
        assertEquals("06c9a7da98c155a32bf2208ab94750a31f3a2a2c", s.infoHash)
        assertEquals(1_352_914_698L, s.size)
        assertEquals(19, s.file?.index)
        assertEquals("Jujutsu Kaisen (2020) S01E01.4k.h264.mkv", s.file?.name)
    }

    @Test fun skipsStreamsWithoutAHash() = assertNull(toSource(mapOf("name" to "Torrentio\n1080p", "url" to "https://…")))

    // trimmed from kitsu.app/api/edge/mappings?filter[externalSite]=anilist/anime&filter[externalId]=113415
    @Test fun readsKitsuMapping() {
        val found = mapOf("data" to listOf(mapOf("relationships" to mapOf("item" to mapOf("data" to mapOf("type" to "anime", "id" to "42765"))))))
        assertEquals(42765, kitsuId(found))
        assertNull(kitsuId(mapOf("data" to emptyList<Any>())))
    }

    @Test fun qualities() {
        assertEquals("1080p", quality("Torrentio\n1080p HDR"))
        assertEquals("720p", quality("Torrentio\n720p"))
        assertEquals("unknown", quality(null))
    }
}
