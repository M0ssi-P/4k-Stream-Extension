# Writing a 4k Stream plugin

A plugin is a small Kotlin library that finds streams for a title. You write it once; the same build runs on the
desktop app, Android phones and Android TV. Plugins live in a GitHub repo, and users add that repo to
**Settings › Extensions** with one link:

```
https://raw.githubusercontent.com/<you>/<repo>/builds/plugins.json
```

Plugin API version: **1**.

There are two kinds of plugin:

| Kind | Returns | What the app does |
|---|---|---|
| `online` | Direct links: HLS (`.m3u8`), DASH (`.mpd`) or progressive MP4/MKV | Plays the link |
| `torrent` | A magnet, an info-hash or a `.torrent` URL | Sends it to the user's TorBox or FebBox, waits until it's cached, then plays from there. The app never downloads torrents itself. |

---

## 1. Start from the template

Copy the template repo (`4kstream-extensions`). It has:

```
4kstream-extensions/
  settings.gradle.kts        every folder with a plugin.json is a plugin
  build.gradle.kts           the `make` task: jar → d8 → .4ks, plus plugins.json
  gradle.properties          the API coordinate, the repo's name and author
  OpenMovies/                a sample online plugin
    plugin.json
    src/main/kotlin/…/OpenMovies.kt
  OpenTorrents/              a sample torrent plugin
  .github/workflows/build.yml
```

To add a plugin, make a new folder with a `plugin.json` and a class that extends `fourk.plugin.Provider`.

### plugin.json

```json
{
  "internalName": "Projector",
  "name": "Projector",
  "version": "3",
  "kind": "online",
  "languages": ["en"],
  "types": ["movie", "series"],
  "ids": ["tmdb", "imdb"],
  "search": true,
  "minApi": 1,
  "entry": "com.example.projector.Projector",
  "settings": [
    { "key": "apikey", "label": "API key", "type": "secret", "required": true },
    { "key": "region", "label": "Region", "type": "select", "options": ["us", "uk"], "default": "us" },
    { "key": "hd_only", "label": "HD only", "type": "toggle", "default": false }
  ]
}
```

| Field | Meaning |
|---|---|
| `internalName` | Stable id. Never change it, or users lose their settings. Letters and digits only. |
| `name` | Shown in the app. |
| `version` | Raise it on every release (`"3"` → `"4"`, or semver `"1.2.0"`). The app shows **Update** when it goes up. |
| `kind` | `online` or `torrent`. |
| `languages` | Audio languages you return (ISO 639-1). |
| `types` | `movie`, `series`, `anime`. The app only asks you for these. |
| `ids` | Which ids you want in `TitleQuery`: `tmdb`, `imdb`, `anilist`, `kitsu`, `mal`. Others are left null. |
| `search` | `true` if you implement `search` and `item` (manual search). |
| `minApi` | The plugin API version you built against. An older app refuses the plugin. |
| `entry` | Your `Provider` class. |
| `settings` | Optional fields the user fills in. `type` is `text`, `secret`, `select` or `toggle`. The app won't call you until every `required` one is set. |

## 2. The API

Plugins compile against `extension-api` (`compileOnly`). The app provides it, and the Kotlin standard library, at runtime.

```kotlin
abstract class Provider {
    lateinit var host: Host                                   // set before any call
    abstract suspend fun streams(query: TitleQuery): List<Source>
    open suspend fun search(query: String, type: String): List<SearchHit> = emptyList()
    open suspend fun item(id: String, season: Int?, episode: Int?): List<Source> = emptyList()
}
```

- **`streams`** is the smart lookup. The app calls it first with everything it knows about the title. Return `emptyList()` when you can't find it; the app then tries the user's other plugins, then manual search.
- **`search` + `item`** are manual search. The user searches your site by name and picks a result; `item` gets that result's `id` and the season/episode they want.

```kotlin
data class TitleQuery(
    val type: String,            // movie | series | anime
    val title: String,           // anime: romaji
    val year: String?,
    val alt: String?,            // anime: English title
    val tmdb: Int?, val imdb: String?, val anilist: Int?, val kitsu: Int?, val mal: Int?,
    val season: Int?,            // null for films and for anime
    val episode: Int?,           // anime: absolute episode number
    val lang: String?,           // the user's preferred audio language
)
```

### Sources

```kotlin
Source(
    name = "Projector 1080p",
    quality = "1080p",              // 2160p, 1080p, 720p, 480p or unknown
    language = "en",
    // online
    server = "Server 2",            // links with the same server are qualities of one stream
    serverLabel = "Fast",           // shown as "Server 2 · Fast"
    url = "https://cdn.example.com/master.m3u8",
    format = "hls",                 // hls, dash or mp4 (any progressive file, MKV included)
    headers = mapOf("Referer" to "https://example.com/"),   // sent on every request, segments included
    subtitles = listOf(Subtitle("https://…/en.vtt", "en", "vtt")),
    expiresAt = 1762170000,         // Unix seconds; after it the app asks you again
)

Source(
    name = "Show S01 1080p WEB",
    kind = "torrent",
    magnet = "magnet:?xt=urn:btih:…",   // or infoHash, or torrentUrl
    size = 18_253_611_008,
    pack = Pack(season = 1),            // the whole season; Pack(1, listOf(1, 2, 3)) for some episodes; Pack(seasons = listOf(1, 2))
    file = FileHint(index = 3),         // or FileHint(name = "Show.S01E04.1080p.mkv")
)
```

How the player uses online sources:
- each `server` becomes one card with its qualities under it;
- if a server fails, stalls for 8 seconds or returns 403, the player moves to the next server at the same timestamp.

How the app picks the episode in a torrent:
1. your `file` hint;
2. then a filename match (`S01E04`, `1x04`, `E04`, an absolute anime number);
3. otherwise, the user picks from the list.

The app remembers the info-hash and file it picked, and de-duplicates torrents by info-hash across all plugins.

## 3. The helpers (`host`)

Use these instead of bundling your own libraries:

| Helper | What it does |
|---|---|
| `host.http.get(url, headers)`, `.post(url, body, contentType, headers)`, `.postForm(url, form, headers)` | The app's network, so the user's DNS and proxy settings apply. Cookies are kept per plugin for the session. Returns `Response(code, text, headers, finalUrl)`. |
| `host.html.parse(html, baseUrl)` | Jsoup underneath: `select(css)`, `selectFirst(css)`, `attr`, `absUrl`, `text`, `html`. |
| `host.json.parse(text)` / `stringify(value)` | Maps, lists, strings, numbers, booleans. |
| `host.crypto` | `base64Decode/Encode`, `md5`, `sha256`, `aesCbcDecrypt/Encrypt` (AES/CBC/PKCS5). |
| `host.unpack(script)` | Unpacks `eval(function(p,a,c,k,e,d)…)` player scripts. |
| `host.setting(key)` | The user's value for one of your settings, or its default. Secrets are stored encrypted, and only your plugin can read them. |
| `host.log(message)` | Shows in the app's log for your plugin. |

## 4. Rules

- **No Android imports, no app classes.** Only `fourk.plugin.*`, the Kotlin standard library and the JDK. The same classes run on the desktop JVM and, after `d8`, on Android.
- **Don't bundle libraries.** The jar holds only your own classes. Use the helpers above. A plugin that needs another library won't load on Android.
- **No coroutines library.** `suspend` works, but don't depend on `kotlinx.coroutines`. Use `host.http`, which is already suspending.
- **Be quick.** Every call has a **10-second** timeout. Anything slower is skipped so the user isn't kept waiting.
- **Throw on errors.** The app catches everything your plugin throws and shows its message on the provider's card. A crash never takes the app down.
- **Never ask for the user's TMDB, TorBox or FebBox tokens.** The API doesn't expose them, and settings must not ask for them.
- **Don't track users.** Calls carry no user id.
- **Build with Kotlin ≤ the app's version** (the template pins it in `gradle.properties`).

## 5. The .4ks package

`./gradlew make` turns each plugin into `build/repo/<internalName>.4ks`, a zip with:

| Entry | For |
|---|---|
| `manifest.json` | `internalName`, `name`, `version`, `api`, `entry` |
| `classes.jar` | the desktop app (loaded with `URLClassLoader`) |
| `classes.dex` | Android (`d8` output, loaded with `DexClassLoader` from app-private storage, read-only) |

and writes `build/repo/plugins.json`:

```json
{
  "api": 1,
  "name": "My Plugins",
  "author": "me",
  "plugins": [
    { "internalName": "Projector", "name": "Projector", "version": "3", "kind": "online", "languages": ["en"],
      "types": ["movie", "series"], "ids": ["tmdb"], "search": true, "settings": [],
      "url": "Projector.4ks", "sha256": "…", "minApi": 1 }
  ]
}
```

`url` may be relative to `plugins.json`. The app downloads the package and refuses it if its SHA-256 doesn't match. It also refuses a `minApi` newer than the app.

`make` needs the Android SDK for `d8`: set `ANDROID_HOME`, or `sdk.dir` in `local.properties`.

## 6. The workflow

`.github/workflows/build.yml` runs on every push to `main`:

1. Sets up JDK 17. GitHub's Ubuntu runners already have the Android SDK.
2. Runs `./gradlew make`, which builds every plugin.
3. Commits `build/repo` (the `.4ks` files and `plugins.json`) to the `builds` branch.

One setting, before the first push: add a repository **variable** `FOURK_API` with the JitPack coordinate of the plugin API:

```
com.github.<owner>.<app-repo>:extension-api:<tag>
```

Locally, publish the API from the app repo instead (`./gradlew :extension-api:publishToMavenLocal`). The template's default coordinate (`fourk:extension-api:1.0.0`) then resolves from `mavenLocal`.

## 7. Versioning

- **Your plugin.** Raise `version` in its `plugin.json` and push. The workflow rebuilds, `plugins.json` gets the new version and checksum, and users see **Update**. Keep `internalName` the same forever.
- **Removing a plugin.** Delete its folder. Users who had it see **Removed by author**, and it stops being called.
- **The API.** Additive changes keep `API_VERSION = 1`. A breaking change raises it. Set `minApi` to the version you built against: older apps refuse the plugin and show "needs a newer 4k Stream" instead of crashing.

## 8. Testing

1. Publish the API locally: in the app repo, run `./gradlew :extension-api:publishToMavenLocal`.
2. In the plugin repo, run `./gradlew make`.
3. Serve the output with `cd build/repo && python -m http.server 8788`.
4. In the app, open **Settings › Extensions** and add `http://127.0.0.1:8788/plugins.json`. From the Android emulator, use `http://10.0.2.2:8788/plugins.json`; debug builds allow plain HTTP to those local addresses only.

## 9. Your responsibility

4k Stream ships with no sources of its own besides the default plugin repo. You are responsible for what your plugin links to: only link to content you have the right to share. Plugins run code on the user's device, which is why users are told to add only repos they trust. Write yours so it deserves that trust.
