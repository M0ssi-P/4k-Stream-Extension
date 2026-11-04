package fourk.torrentio

import fourk.plugin.*

private const val BASE = "https://torrentio.strem.fun"

/** Torrents from Torrentio (a Stremio addon), looked up by IMDb id, or by Kitsu id for anime. */
class Torrentio : Provider() {
    override suspend fun streams(query: TitleQuery): List<Source> {
        // Torrentio only knows Kitsu ids for anime, so translate the app's AniList id first
        val anilist = query.anilist
        val q = if (query.type == "anime" && query.kitsu == null && anilist != null) query.copy(kitsu = kitsuFor(anilist)) else query
        val path = streamPath(q) ?: return emptyList()
        val res = host.http.get("$BASE/stream/$path.json", emptyMap())
        check(res.code == 200) { "Torrentio returned HTTP ${res.code}" }
        @Suppress("UNCHECKED_CAST")
        val streams = (host.json.parse(res.text) as Map<String, Any?>)["streams"] as? List<Map<String, Any?>>
        return streams.orEmpty().mapNotNull(::toSource)
    }

    // shortcut: one Kitsu lookup per call, no cache; add one if Kitsu starts rate-limiting
    private suspend fun kitsuFor(anilist: Int): Int? {
        val res = host.http.get("$KITSU/mappings?filter%5BexternalSite%5D=anilist%2Fanime&filter%5BexternalId%5D=$anilist", emptyMap())
        check(res.code == 200) { "Kitsu returned HTTP ${res.code}" }
        return kitsuId(host.json.parse(res.text))
    }
}

private const val KITSU = "https://kitsu.app/api/edge"

/** Kitsu mapping response → the anime id at data[0].relationships.item.data.id, or null when unmapped. */
internal fun kitsuId(json: Any?): Int? {
    val first = ((json as? Map<*, *>)?.get("data") as? List<*>)?.firstOrNull() as? Map<*, *>
    val item = ((first?.get("relationships") as? Map<*, *>)?.get("item") as? Map<*, *>)?.get("data") as? Map<*, *>
    return (item?.get("id") as? String)?.toIntOrNull()
}

/** movie/tt123, series/tt123:1:4, or series/kitsu:42:4 for anime (absolute episode). Null when we can't ask. */
internal fun streamPath(q: TitleQuery): String? {
    if (q.type == "anime" && q.kitsu != null && q.episode != null) return "series/kitsu:${q.kitsu}:${q.episode}"
    val imdb = q.imdb?.let { if (it.startsWith("tt")) it else "tt$it" } ?: return null
    return when {
        q.type == "movie" -> "movie/$imdb"
        q.season != null && q.episode != null -> "series/$imdb:${q.season}:${q.episode}"
        else -> null
    }
}

/** One Torrentio stream → a torrent Source. Title is "release name\n[file name\n]👤 seeders 💾 size ⚙️ site…". */
internal fun toSource(s: Map<String, Any?>): Source? {
    val hash = s["infoHash"] as? String ?: return null
    val title = s["title"] as? String ?: ""
    val hints = s["behaviorHints"] as? Map<*, *>
    return Source(
        name = title.lineSequence().first(),
        quality = quality(s["name"] as? String),
        language = "en",
        kind = "torrent",
        infoHash = hash,
        size = size(title) ?: 0L, // the API wants a Long; 0 = unknown
        file = FileHint(index = (s["fileIdx"] as? Number)?.toInt(), name = hints?.get("filename") as? String),
    )
}

/** Torrentio's name line is "Torrentio\n4k HDR", "Torrentio\n1080p" … */
internal fun quality(name: String?): String {
    val n = name.orEmpty().lowercase()
    return when {
        "4k" in n || "2160" in n -> "2160p"
        "1080" in n -> "1080p"
        "720" in n -> "720p"
        "480" in n -> "480p"
        else -> "unknown"
    }
}

private val SIZE = Regex("""💾\s*([\d.]+)\s*([KMGT])B""")

internal fun size(title: String): Long? {
    val (n, unit) = SIZE.find(title)?.destructured ?: return null
    return (n.toDouble() * Math.pow(1024.0, "KMGT".indexOf(unit) + 1.0)).toLong()
}
