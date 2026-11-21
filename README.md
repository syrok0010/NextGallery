# NextGallery

A native Android gallery client for [Nextcloud Memories](https://memories.gallery/) that brings photos and videos on your phone and on your Nextcloud server together in a single timeline.

NextGallery is under active development and currently in pre-release. Core browsing, video playback, albums, manual uploads, and deletion already work, though local data compatibility between versions is not yet guaranteed.

## Features

- **Unified timeline** — view local photos and videos alongside your Nextcloud Memories archive grouped by day. Matching local and cloud copies are merged into one item, and local caching speeds up app startup.
- **Full-screen photo & video viewer** — pinch-to-zoom and swipe gestures, bottom thumbnail strip, playback for local and cloud videos, frame-by-frame scrubbing, and server-side video quality switching via Memories transcoding.
- **Albums & device folders** — browse your Nextcloud albums and local device folders, and add items from the timeline or viewer to an existing or newly created Nextcloud album (phone-only items are uploaded first).
- **Manual background uploads** — upload selected photos and videos in the background with chunked transfers for large files, automatic retries on temporary failures, and a dedicated uploads screen to track progress, retry, or cancel.
- **Trash-safe deletion** — move selected items to the Android trash and/or the Nextcloud trash; when both copies exist, you choose which copy to delete.
- **Secure sign-in & languages** — sign in to your Nextcloud server through your browser without entering your password into the app. Available in English and Russian.

## Requirements

- Android 12 (API 31) or newer.
- A Nextcloud server with [Memories](https://memories.gallery/) **11 or newer** installed and configured.

## Installation

### Prebuilt APK

Download the latest APK from [GitHub Releases](https://github.com/syrok0010/NextGallery/releases) (checksums are published in `SHA256SUMS`).

### Build from source

1. Open the project in Android Studio and wait for Gradle sync (or run `./gradlew :app:assembleDebug`).
2. Connect an Android 12+ device or start an emulator.
3. Run the `app` configuration.

### First sign-in

Enter your Nextcloud server URL, approve the login in your browser, and grant media permissions if you want local photos and videos to appear in the timeline.

## Current Limitations

- **Single account:** only one Nextcloud/Memories account is supported at a time; signing out clears all local app data.
- **Server required:** Nextcloud with Memories 11+ is required — there is no standalone offline-only mode for local photos.
- **Full media access:** showing local media requires full photo and video access on Android (Android Selected Photos Access is not supported yet).
- **Cache is not offline mode:** previews and metadata are cached for faster startup, not as a guaranteed offline archive.
- **Actions inside albums:** uploading, adding to albums, and deleting are currently available from the main timeline and its viewer, not from inside an opened album.
- **Shared albums:** adding items currently supports your own Nextcloud albums only.

## Roadmap

- Media actions inside opened albums and freeing up device space for backed-up items.
- Shared albums and convenient album/photo sharing.
- Automatic background sync for new photos and videos with network and charging constraints.
- Favorites, map view, faces, and other discovery views.

## For Developers

Architecture decision records (ADRs), API research, and the domain glossary live in [docs/README.md](docs/README.md) and [CONTEXT.md](CONTEXT.md) (currently written in Russian). Tasks and discussions are tracked in [GitHub Issues](https://github.com/syrok0010/NextGallery/issues).
