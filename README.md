# Pure Gallery

A private, local-first Android photo and video gallery.

## Build
Push to `main` or run **Actions → Android APK → Run workflow**. Download the
`PureGallery-debug` artifact from the completed workflow run.

## Current foundation
- Kotlin + Jetpack Compose
- MediaStore-based local media access
- Android 13+ image/video permissions, including Android 14 selected-photo access
- GitHub Actions debug APK build

This repository is under active development. Features such as persistent custom
labels, the app-managed permanent recycle bin, and safe restore/permanent-delete
flows are not yet implemented in this initial foundation.
