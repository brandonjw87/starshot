# StarShot

Night-sky / long-exposure camera for the Pixel 10 Pro XL (Camera2, Kotlin, Compose). No ads, no internet permission, no analytics.

- Loud countdown (default 10 s) on the alarm stream with voice, beeps, vibration; dark screen during exposure; "done" chime.
- Auto mode: meters the sky after the countdown, caps per-frame exposure at min(sensor max, NPF rule), ISO up to just past max analog gain, frame count for the preset's total integration.
- Stack mode with star alignment (star detection → translation voting → least-squares similarity fit → warp → sigma-clipped mean). Star Trails (lighten), Single Long, Timelapse.
- Saves to Pictures/StarShot/<session>/: per-frame DNG (DngCreator), 16-bit linear TIFF stack, stretched JPEG with EXIF (+GPS if enabled).
- Red night UI, bubble level/aim indicator, motion guard, battery/thermal warnings, storage and time estimates, presets, session gallery.

Build: `./gradlew testDebugUnitTest assembleDebug`
