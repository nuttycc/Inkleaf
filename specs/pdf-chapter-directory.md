# Spec: PDF Chapter Directory Import and Continuous Reading

Status: Draft for review
Last updated: 2026-07-03

## Assumptions I'm Making

1. This feature is about a folder that contains multiple PDF chapter files for one comic, not a PDF file's internal bookmarks or table of contents.
2. First version PDF support is scoped to chapter files inside a selected series folder. Standalone PDF-as-one-book import is not included unless explicitly added later.
3. In the main import flow, file selection means independent comic files; folder selection means one comic made of chapter PDFs.
4. Existing zip/cbz reading must keep working.
5. Existing "manga library folder" management remains a separate library-sync concept, but user-facing wording should avoid confusing it with "series manga folder" import.
6. Chapter order is controlled by filenames using natural chapter sorting. The app should not depend on raw filesystem/provider enumeration order, because that order is not stable across devices or file managers.

Correct these before implementation if any are wrong.

## Objective

Add support for importing a local folder as one manga whose chapters are PDF files.

The target user organizes a local manga like this:

```text
One Piece/
├── 1-3.pdf
├── 3.5.pdf
└── 4.pdf
```

After importing the folder, the shelf should show one manga, "One Piece". Opening it should allow the user to read the PDFs in chapter order as a continuous manga, preserving progress as chapter plus page.

This prevents the shelf from being filled with one card per chapter while still respecting the user's folder organization.

## Tech Stack

- Android app, single `app/` module
- Kotlin
- Jetpack Compose UI
- Room database
- Android Storage Access Framework for file and folder permissions
- Preferred PDF rendering approach: PDFium, pending implementation-plan validation of Android wrapper choice, APK size impact, and maintenance/security status
- Existing zip/cbz reader support must remain intact

## Commands

Run from repository root:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
```

Use `connectedDebugAndroidTest` when changing Compose UI, navigation, storage permissions, or Android framework behavior.

## Project Structure

```text
app/src/main/java/com/exio/inkleaf/
├── data/                  # repositories, scanners, cache, preferences
├── data/db/               # Room database entities and DAOs
├── ui/                    # Compose screens and view models
├── ui/theme/              # app theme
└── MainActivity.kt        # app entry point and navigation

app/src/test/java/         # local JVM tests
app/src/androidTest/java/  # instrumented Android/Compose tests
docs/specs/                # feature specifications
```

Current baseline:

- `ComicEntity` currently represents one shelf manga as one file-backed record.
- The existing reader opens zip/cbz files containing image pages.
- The existing library scanner currently discovers `.zip` and `.cbz` files.
- There is no chapter-level data model yet.

## User-Facing Concepts

### Independent Comic File

A file selected through the file picker is treated as an independent manga entry.

For this spec, the independent file flow primarily preserves existing zip/cbz behavior. Multi-selected files should not be merged into one manga.

### Series Manga Folder

A folder selected through the series-folder import flow is treated as one manga.

- Folder name becomes the manga title.
- Direct child PDF files become chapters.
- Non-PDF files are ignored in the first version.
- Nested subfolders are out of scope in the first version.

### Manga Library Folder

A manga library folder is an existing library-management concept for scanning many independent manga files.

This feature should not accidentally change a library folder full of unrelated files, such as:

```text
Manga Library/
├── One Piece.zip
└── Naruto.zip
```

Those files should continue to be treated as separate manga entries.

## User Stories

1. As a reader, I can import a folder of PDF chapters so the shelf shows one manga instead of many chapter files.
2. As a reader, I can continue reading from the last chapter and page I reached.
3. As a reader, I can add a new PDF chapter to the same folder and have the app discover it on rescan.
4. As a reader, I can move through chapter boundaries naturally while reading.
5. As a reader, I can still import and read existing zip/cbz manga without behavior changes.

## Functional Requirements

### Import

- The main import flow must make the import intent clear:
  - file picker: independent comic files
  - folder picker: one manga made of PDF chapters
- Importing a series folder creates one shelf manga.
- The default manga title is the selected folder name.
- A folder with no PDF files should not create an empty manga silently; the user should receive a clear message.
- Non-PDF files in the selected folder are ignored for the first version.

### Chapter Discovery

- Direct child PDF files of the selected folder are chapters.
- Chapter display names come from PDF filenames without the `.pdf` extension.
- Adding a new PDF to an already imported series folder should add a new chapter after rescan.
- Existing chapter progress should survive adding new chapters.
- Missing chapter files should be represented in a way that does not crash reading or erase unrelated progress.

### Chapter Sorting

- The app must not rely on the raw directory enumeration order returned by Android or a document provider.
- Default order is natural filename/chapter sorting.
- Examples:
  - `2.pdf` sorts before `10.pdf`
  - `3.5.pdf` sorts before `4.pdf`
  - `1-3.pdf` sorts before `3.5.pdf`
- Users can control stable order by naming files clearly, for example:

```text
001.pdf
002.pdf
003.pdf
```

or:

```text
001 1-3.pdf
002 3.5.pdf
003 4.pdf
```

- First version does not support app-side manual chapter reordering.

### Reading

- Opening the shelf manga starts at the saved chapter and page, or the first chapter if unread.
- Reaching the end of one chapter and continuing forward enters the next chapter.
- Chapter transitions should feel lightweight and natural; they should not force the user back to the shelf.
- Reading progress is stored as chapter plus page, not a single page number across all PDFs.
- Progress should remain valid when a later chapter is added.

### Shelf Display

- The shelf shows one card for the imported series folder.
- The card title defaults to the folder name.
- The card should communicate unread or current progress without pretending the whole folder is one physical PDF.
- Default cover comes from the first page of the first sorted chapter when possible.

### Errors and Edge Cases

- Empty folder: clear message, no crash.
- Folder with no PDFs: clear message, no crash.
- Corrupt PDF chapter: clear message when encountered, no app crash.
- Password-protected or encrypted PDF chapter: clear message, no app crash.
- Permission revoked or folder deleted: clear message and recoverable state.
- One broken chapter should not automatically delete the entire manga or unrelated progress.

## Non-Goals

- Do not merge PDFs into a new PDF file.
- Do not support PDF internal bookmarks/table of contents.
- Do not support zip/cbz as chapters inside the same series folder in the first version.
- Do not support nested folders as chapters in the first version.
- Do not add online metadata lookup, automatic title recognition, or online cover download.
- Do not add app-side manual chapter ordering in the first version.

## Code Style

Follow the existing Kotlin and Compose style:

- 4-space indentation
- PascalCase for composable functions and screens
- `FeatureViewModel` naming for view models
- Data-layer logic in `data/`
- Room entities and DAOs in `data/db/`
- Compose screens and view models in `ui/`
- Resource names in lowercase with underscores

Example style:

```kotlin
data class ChapterProgress(
    val chapterIndex: Int,
    val pageIndex: Int,
)

fun ChapterProgress.coerceTo(chapterCount: Int, pageCount: Int): ChapterProgress =
    copy(
        chapterIndex = chapterIndex.coerceIn(0, (chapterCount - 1).coerceAtLeast(0)),
        pageIndex = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
    )
```

## Testing Strategy

Use focused tests based on risk:

- Local JVM tests for chapter filename parsing and sorting.
- Local JVM tests for folder scan diff behavior where practical.
- Instrumented tests or manual device checks for Storage Access Framework folder permissions.
- Compose/manual checks for import entry wording, shelf display, reader progress, and chapter transitions.

Minimum verification before calling the feature done:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Run instrumented tests or a manual emulator/device pass for folder selection, rescan, and reading if UI/storage behavior changes.

## Boundaries

- Always: preserve existing zip/cbz import and reading behavior.
- Always: keep independent-file import separate from series-folder import.
- Always: store progress in a way that survives adding later chapters.
- Always: show clear messages for empty, unreadable, or invalid folders/files.
- Always: keep user files untouched; never modify, rename, move, or delete original manga files.

- Ask first: choosing the specific PDFium Android wrapper after comparing maintenance status, APK size impact, supported ABIs, license, and security update posture.
- Ask first: switching away from PDFium to Android framework `PdfRenderer` or another PDF rendering approach.
- Ask first: expanding scope to standalone PDF-as-one-book import.
- Ask first: supporting zip/cbz files as chapters.
- Ask first: changing or removing the existing manga library folder concept.
- Ask first: adding manual chapter reordering.

- Never: rely on raw directory enumeration order as the only chapter order.
- Never: silently merge unrelated manga files into one series.
- Never: erase reading progress just because a chapter is temporarily missing.
- Never: remove or weaken existing favorite, cover, cache, or group behavior without explicit approval.
- Never: commit secrets, SDK paths, keystores, passwords, or build outputs.

## Success Criteria

- Importing a folder containing `1-3.pdf`, `3.5.pdf`, and `4.pdf` creates exactly one shelf manga.
- The shelf manga title defaults to the folder name.
- The reading order is `1-3`, then `3.5`, then `4`.
- `2.pdf` sorts before `10.pdf`.
- Reopening the manga restores the last read chapter and page.
- Continuing forward from the last page of a chapter opens the next chapter.
- Adding `5.pdf` to the folder and rescanning adds a new chapter without losing old progress.
- Selecting multiple independent files still imports them as separate manga entries.
- A folder containing unrelated `One Piece.zip` and `Naruto.zip` remains a multi-book library scenario, not a single PDF chapter series.
- Empty folders, folders with no PDFs, corrupt PDFs, and revoked permissions produce clear user-visible messages and do not crash the app.
- Existing zip/cbz books can still be imported, opened, favorited, assigned covers, grouped, and removed as before.

## Open Questions

1. Should standalone PDF files selected through the file picker become independent manga in a later phase, or should PDF remain folder-chapter-only for now?
2. Should the app show an explicit chapter list before opening the reader, or is continuous reader navigation enough for the first version?
3. What exact Chinese labels should distinguish "series manga folder" from the existing "manga library folder"?
