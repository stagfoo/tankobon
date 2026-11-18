# Tankobon

A manga reader for two-screen Android handhelds (built for the AYN Thor), held sideways like
a book, the way DS training games were: the page fills the big screen, and the small screen
beside it holds the controls.

- **Big screen:** the bookshelf (covers, three across), then the page, fitted to the screen.
- **Small screen:** the selected series and its chapters; while reading, the page number, a
  scrubber, big turn buttons, chapter skip and the bookshelf button.
- Right-to-left by default (manga); left-to-right is one tap away.
- Turn pages by tapping the page's left/right third, swiping, the turn buttons, A/B, L/R or
  the d-pad (which is re-mapped to match the sideways screen).
- Remembers your place per series; finishing a chapter marks it read (long-press a chapter to
  toggle). Progress can go to Next Up, a personal recommendations server.

Without a second screen, the controls sit under the page.

## Your manga

Put it in a `Manga` folder at the top of internal storage or the SD card:

```
Manga/
  Series name/
    Chapter 1.cbz
    Chapter 2.cbz
  Another series/
    Vol 01/        <- folders of images work too
      001.jpg
```

`.cbz`/`.zip` and folders of JPEG, PNG, WebP or AVIF are read. Chapters sort naturally
(2 before 10, 2.5 after 2).

## Settings and the reading log

**Settings** (on the small screen, from the bookshelf or while reading): page order, tap-to-turn,
locking the picture so it stops turning with the Thor, flipping the small screen, sending progress
to Next Up now, and looking for new manga.

Progress is also kept where you can get at it, in `Documents/Tankobon/` on internal storage:

- `reading-log.csv` — one line per sitting with a chapter (pages from–to, minutes) and per
  finished chapter. Opens in any spreadsheet.
- `progress.json` — where every series is up to and which chapters are read. If the app is
  reinstalled, it picks its progress back up from here.

## Install

Add this repo to [Obtainium](https://github.com/ImranR98/Obtainium) (`https://github.com/stagfoo/tankobon`),
or install the APK from Releases. Grant All files access when asked, or over adb:

```sh
adb shell appops set com.tankobon.app MANAGE_EXTERNAL_STORAGE allow
```

## Next Up (optional)

Build with a `nextup.properties` (gitignored) holding `url=https://…`, then hand the app its
key once over adb, so nobody types it on the handheld:

```sh
adb shell am start -n com.tankobon.app/.MainActivity --es key <key>
```

Progress is sent to `POST /api/ingest/manga` when you leave a book or the app.

## Build and release

```sh
./gradlew :app:testDebugUnitTest :app:assembleRelease
scripts/release.sh   # bumps the version, tests, builds, tags, publishes a GitHub release
```
