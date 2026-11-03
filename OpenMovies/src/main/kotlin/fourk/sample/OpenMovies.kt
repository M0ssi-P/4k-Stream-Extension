package fourk.sample

import fourk.plugin.*

/**
 * A sample online plugin. It only knows two Blender open movies and returns public test streams for them:
 * Big Buck Bunny as HLS, DASH and MP4 (plus one dead mirror, to show failover) and Sintel as HLS.
 */
class OpenMovies : Provider() {
    private val bbb = listOf(
        Source(name = "Dead mirror", server = "Dead mirror", quality = "1080p", language = "en", url = "http://127.0.0.1:9/gone.m3u8", format = "hls"),
        Source(name = "Mux", server = "Server 1", serverLabel = "Fast", quality = "1080p", language = "en",
            url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", format = "hls"),
        Source(name = "Akamai", server = "Server 2", serverLabel = "Backup", quality = "1080p", language = "en",
            url = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd", format = "dash"),
        Source(name = "Google", server = "Server 3", quality = "720p", language = "en",
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4", format = "mp4"),
    )
    private val sintel = listOf(
        Source(name = "Bitmovin", server = "Server 1", quality = "1080p", language = "en",
            url = "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8", format = "hls"),
    )

    override suspend fun streams(query: TitleQuery): List<Source> {
        host.log("streams for ${query.title} (tmdb ${query.tmdb}), region ${host.setting("region")}")
        return when {
            query.tmdb == 10378 || query.title.contains("big buck bunny", ignoreCase = true) -> bbb
            query.tmdb == 45745 || query.title.contains("sintel", ignoreCase = true) -> sintel
            else -> emptyList()
        }
    }

    override suspend fun search(query: String, type: String): List<SearchHit> = listOf(
        SearchHit("bbb", "Big Buck Bunny", "2008", subtitle = "Blender open movie"),
        SearchHit("sintel", "Sintel", "2010", subtitle = "Blender open movie"),
    ).filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) || query.contains(it.title, ignoreCase = true) }

    override suspend fun item(id: String, season: Int?, episode: Int?): List<Source> = when (id) {
        "bbb" -> bbb
        "sintel" -> sintel
        else -> emptyList()
    }
}
