# Disabled unit tests

Nothing in this directory is on any source set, so it is not compiled and not
run. It exists so that tests which no longer match the code they cover stay in
the tree, with a record of what is wrong, instead of being deleted.

To bring a test back, fix it and move it to `../test/` at the same package path.

## SubtitlesModelTest.kt

Written against the OpenSubtitles **v0** API. The integration has since moved to
v1: `OpenSubtitleRepository.queryWithName` / `queryWithHash` now take a language
id list plus a `hearingImpaired` flag and return `OpenSubV1`, where they used to
take different arguments and return a plain list.

What it needs before it can be re-enabled:

- **A fixture for the v1 response.** `TestUtil.createOpenSubtitle` was dropped
  when `TestUtil` was updated, and the replacement has to build the nested
  `OpenSubV1` → `Data` → `Attributes` → `FeatureDetails` / `File` / `Uploader`
  graph in `resources/.../opensubtitles/Models.kt`.
- **Reworked assertions.** `SubtitlesModel` now maps a `Data` to a
  `SubtitleItem` using `attributes.subtitleId`, `attributes.files.first().fileId`,
  `attributes.language` and `attributes.featureDetails.movieName`. The old
  assertions were written against a flatter model, so they need re-deriving
  rather than translating.
- `SubtitlesModel(context, uri, name, coroutineContextProvider)` gained `name`.
- `ExternalSubRepository.getDownloadedSubtitles` and `saveDownloadedSubtitle`
  take a `Uri` where the test passes a `String`, and `saveDownloadedSubtitle`
  gained `hearingImpaired`.

Two unrelated breakages in this file were already fixed before it was moved
here, so they are not waiting for anyone: an IDE auto-import had written
`import main.java.org.videolan.resources.opensubtitles.OpenSubtitleRepository`
using the filesystem path rather than the package, and `Transformations.map`
was replaced with the `LiveData.map` extension the production code uses.
