# Strata TV

Native Android TV / Fire Stick rewrite of the [Strata IPTV
app](https://github.com/Yokomoko/Strata) using Compose for TV +
Kotlin.

## Why a rewrite?

The Flutter v1 app worked but D-pad navigation in a 2D TV Guide layout
was a constant battle on Fire Stick.  Compose for TV's focus model is
purpose-built for D-pad and ExoPlayer integrates natively without a
plugin layer — the two biggest pain points of v1.

The Flutter app stays in the `Strata` repo as a working reference and
fallback while v2 is built.

## Status

Phase 0 — scaffold.  Compose for TV deps wired, Hilt set up, sideloads
to Fire Stick.  See [`STRATA_TV_PLAN.md` in the v1 repo](https://github.com/Yokomoko/Strata/blob/main/STRATA_TV_PLAN.md)
for the full phased plan.

## Build

Requires JDK 17, Android SDK 34, AGP 8.7+.

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Sideload to Fire Stick

```bash
adb connect <fire-stick-ip>
adb install -r app/build/outputs/apk/release/app-release.apk
```

## License

Personal project — not currently published.  See LICENSE if/when added.
