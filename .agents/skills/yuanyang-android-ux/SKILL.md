---
name: yuanyang-android-ux
description: Design, implement, or review native Android interactions in 原样 / Frame Again, including its camera, album library, photo gallery, and export preview. Use for interaction and screen hierarchy changes, not encoder or pose algorithm work.
---

# 原样 Android interaction design

Start with the user's task and the current Android screenshots, not a component library. Compare the proposed flow on a phone-sized surface. Existing Java Views are sufficient; only migrate frameworks if the task requires it.

## Preserve these product decisions

- Launch directly into the camera in the last used album. Camera upper left opens all albums, lower left opens the current album, each in one tap. Selecting an album opens its camera. Keep Android Back coherent.
- Camera previews are the main surface. Keep one fixed centered shutter; put secondary controls around it. Use a dedicated flip-camera icon with a dynamic accessible name and at least a 48 dp hit target. A device with one camera has no flip button and the shutter stays put. Remember the last working facing. Ghost overlays are optional framing aids, not fixed target points or automatic positioning; use a separately labeled “叠影设置” control, and keep opacity controls out of the default layout. The album shortcut shows the latest photo or a gallery icon when empty and must never change the overlay.
- The album library helps people recognize an album by cover and name. The current album opens directly to photo thumbnails grouped by date, with the selected daily photo distinguishable beyond color. A photo opens full screen with pinch and double-tap zoom, pan when zoomed, and access to adjacent photos. Long-press a thumbnail or tap Select to enter batch mode; show the count and confirm before deleting. Replacing the day's selection belongs to the single-photo viewer. Preserve the gallery position when returning.
- The album's primary media action opens a compact export panel. Put daily/all selection and alignment there, not before gallery content. Start the chosen GIF or MP4 within two taps from the album using defaults, with an explicit format choice. Preserve in-app playback and intentional share/Save As; canceling the system picker returns to the preview.

## Review before delivery

1. Inspect the camera and current album screenshots first. Check that the photos are visible without scrolling, the shutter is visually and geometrically centered with either lens, and primary/secondary actions are distinct.
2. Use a disposable emulator with synthetic media. Exercise launch, both direct album routes, selecting an album, selecting/deleting a photo (including cancellation), an export from the sheet, and exiting/reopening its preview. Test both lens directions where hardware allows; verify Center stays fixed even with the flip action missing. Check large text, a narrow phone, accessible icon names and touch targets. Clearly separate tested behavior from untested real-phone conditions.
3. Consult [Android layouts and navigation](https://developer.android.com/design/ui/mobile/guides/layout-and-content/layout-and-nav-patterns) and [Core app quality](https://developer.android.com/docs/quality-guidelines/core-app-quality) when choosing containers or interaction conventions. Prefer native component semantics over generic clickable cards.
