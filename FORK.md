# About this fork

This is a personal fork of [Fossify Gallery](https://github.com/FossifyOrg/Gallery). It is not affiliated with or supported by Fossify. Please do not report problems with this build to the Fossify project, open an issue here instead.

It installs next to the original app: the package name is `org.fossify.gallery.adman` and the default icon is purple instead of green.

## Install

Download the APK from the [latest release](https://github.com/adman234/Gallery/releases/latest), or add `https://github.com/adman234/Gallery` to [Obtainium](https://github.com/ImranR98/Obtainium) to get updates.

Give the app "All files access" when it asks, a few features (system trash, hidden folders) need it.

## What is different

**Speed**

- New photos, screenshots and downloads show up right away. The app asks MediaStore only for what changed since the last check instead of waiting for a rescan of the whole library.
- The fullscreen view stays on the same file when its list refreshes in the background.
- A background refresh no longer cancels a selection in the grid.

**Photos**

- Motion photos (Pixel and the legacy MicroVideo format): play button in the fullscreen view, optional autoplay and looping, a "Motion" badge in the grid and an "Export motion video" action. Based on [FossifyOrg/Gallery#1004](https://github.com/FossifyOrg/Gallery/pull/1004).
- 360 degree photos and wide panoramas open in a sphere viewer, drag to look around and pinch to zoom.
- Brightness, contrast and saturation in the photo editor (menu, Adjust).

**Videos**

- HDR videos are tone-mapped to SDR, so they no longer look dark or washed out.
- Video editor: trim, change speed (1/8x to 4x, useful for high frame rate clips) and remove audio. Open a video and press Edit. The result is saved as a new file.

**Browsing**

- A "Recent" folder with the newest 100 items on top of the folder list (can be turned off in the settings).
- Going back from the fullscreen view scrolls the grid to the item you were looking at.
- Folders remember their scroll position while the app is running.
- "Show favorites only" in the menu of every folder.

**Privacy and system integration**

- Optional: strip GPS and EXIF metadata when sharing (off by default). From [FossifyOrg/Gallery#1048](https://github.com/FossifyOrg/Gallery/pull/1048).
- Optional: move deleted files to the system trash instead of the app's Recycle Bin (off by default, Android 11+).
- Favorites are synced with the system favorites on Android 11+. From [FossifyOrg/Gallery#872](https://github.com/FossifyOrg/Gallery/pull/872).

**Fixes**

- Favorites no longer disappear after a background scan gets interrupted ([FossifyOrg/Gallery#383](https://github.com/FossifyOrg/Gallery/issues/383)).
- Several small fixes taken from open upstream pull requests: #986, #1085, #1086, #1087, #1095 and #1154.

## License

Same as upstream: [GPL-3.0](LICENSE). The source of every released APK is the tagged commit in this repository.
