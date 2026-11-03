package fourk.sample

import fourk.plugin.*

/** A sample torrent plugin: the public Big Buck Bunny WebTorrent demo. The app sends it to the user's TorBox / FebBox. */
class OpenTorrents : Provider() {
    override suspend fun streams(query: TitleQuery): List<Source> =
        if (query.tmdb != 10378 && !query.title.contains("big buck bunny", ignoreCase = true)) emptyList()
        else listOf(Source(name = "Big Buck Bunny (WebTorrent demo)", quality = "1080p", language = "en", kind = "torrent",
            magnet = "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Big+Buck+Bunny", size = 276_134_947,
            file = FileHint(name = "Big Buck Bunny.mp4")))
}
