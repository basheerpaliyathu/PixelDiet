# PixelDiet

**A pure-JVM Android image-compression library.** No native code, no `.so`, no Android 15 16 KB
page-size headaches — plus WebP output for smaller files than JPEG. PixelDiet keeps the proven
WeChat-Moments-style sizing strategy from [Luban](https://github.com/Curzibn/Luban) but modernizes
the API for coroutines, scoped storage, and current Android.

[![](https://jitpack.io/v/basheerpaliyathu/PixelDiet.svg)](https://jitpack.io/#basheerpaliyathu/PixelDiet)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

---

## Why PixelDiet?

Android's most popular compressor, [Luban 2](https://github.com/Curzibn/Luban), is excellent — but it
ships a native `libjpeg-turbo` (`.so`) and is JPEG-centric. PixelDiet deliberately trades the native
encoder for two things that matter in 2026:

| | AdvancedLuban (origin) | Luban 2 (latest) | **PixelDiet** |
|---|---|---|---|
| Language / async | Java + RxJava2 | Kotlin + coroutines | **Kotlin + coroutines, Java callbacks** |
| `content://` Uri / scoped storage | ❌ | ✅ | ✅ |
| **Target output size** (`maxSize`, `hardCap`) | ✅ (`setMaxSize`) | ❌ **not supported** | ✅ **guaranteed** |
| Bundled native `.so` | none | **libjpeg-turbo** | **none (verified in CI)** |
| Android 15 16 KB page-size risk | n/a | depends on `.so` alignment | **none — no native code** |
| WebP lossy/lossless output | ❌ | limited | ✅ |
| Extra runtime deps | RxJava2 | — | coroutines + androidx-exifinterface only |

> **Luban 2 target-size migration note:** Luban 2 removed the `setMaxSize()` API that existed in
> the original Luban/AdvancedLuban. If your code relied on a target-size cap, migrating to Luban 2
> will silently break it. PixelDiet restores this capability — and enforces it harder, with a
> two-phase quality-loop + resize-fallback so the output is *actually* ≤ your target.

If you ship `targetSdk 35`, every bundled native library must be
[16 KB-aligned](https://developer.android.com/guide/practices/page-sizes) or your app crashes on
16 KB-page devices (a Google Play requirement since Nov 2025). PixelDiet has **no native code at
all**, so this class of problem simply does not exist — and CI fails if a `.so` ever sneaks in.

## Install (JitPack)

`settings.gradle.kts`:
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.github.basheerpaliyathu.PixelDiet:pixeldiet:0.1.0")
}
```

> This is a multi-module repo, so the coordinate is `com.github.<user>.<Repo>:<module>:<tag>` —
> the group includes `.PixelDiet` and the artifact is the `pixeldiet` library module
> (the `sample` app module is not published).

## Usage

### Kotlin (coroutines)
```kotlin
// Single image (e.g. a Photo Picker Uri) -> WebP
val file: File = PixelDiet.with(context)
    .load(uri)
    .format(OutputFormat.WEBP_LOSSY)
    .getFirst()

// Many images, results in input order
val files: List<File> = PixelDiet.with(context)
    .loadUris(uris)
    .ignoreBy(100)               // skip anything under 100 KB
    .setTargetDir(cacheDir)
    .get()

// Stream results as they finish (progress UIs)
PixelDiet.with(context).loadUris(uris).asFlow().collect { done -> /* ... */ }
```

### Java (callbacks, no RxJava)
```java
PixelDiet.with(context)
    .load(uri)
    .format(OutputFormat.WEBP_LOSSY)
    .launch(new OnCompressListener() {
        @Override public void onStart() { }
        @Override public void onSuccess(File file) { /* main thread */ }
        @Override public void onError(Throwable e) { }
    });
```

### Custom targets
```kotlin
PixelDiet.with(context)
    .load(file)
    .maxSize(200)        // KB  (switches to the custom gear)
    .maxWidth(1080)
    .get()
```

## Compression strategy ("gears")

| Gear | Constant | Behaviour |
|---|---|---|
| Third (default) | `PixelDiet.GEAR_THIRD` | WeChat-Moments-style; great size/quality balance |
| First | `PixelDiet.GEAR_FIRST` | aggressive single-pass downscale |
| Custom | `PixelDiet.GEAR_CUSTOM` | driven by `maxSize` / `maxWidth` / `maxHeight` |

## Migrating from Luban / AdvancedLuban

**Basic compression** — the callback shape is preserved:
```diff
- Luban.compress(context, file).putGear(THIRD_GEAR).launch(listener)   // AdvancedLuban
+ PixelDiet.with(context).load(file).putGear(GEAR_THIRD).launch(listener)  // PixelDiet
```

**Target size** — restored and stronger than the original:
```diff
- Luban.compress(context, file).putGear(CUSTOM_GEAR).setMaxSize(200).launch(listener)
+ PixelDiet.with(context).load(file).maxSize(200).launch(listener)     // GEAR_CUSTOM implicitly
// — or — keep smart sizing AND add a hard size cap:
+ PixelDiet.with(context).load(file).hardCap(200).launch(listener)     // GEAR_THIRD + cap
```

**Migrating from Luban 2** (which dropped `setMaxSize` entirely):
```kotlin
// Luban 2 — no way to cap the output size
Luban.with(context).load(uri).launch(object : OnCompressListener { ... })

// PixelDiet — target size is back, with a two-phase guarantee
PixelDiet.with(context).load(uri).hardCap(200).launch(listener)
```

The main differences from AdvancedLuban: you can pass a `Uri`, no RxJava on your classpath, and
`hardCap()` is a true guarantee (quality loop + resize fallback), not just a geometry hint.

## Requirements
- `minSdk 21`, `compileSdk 35`
- Kotlin coroutines + `androidx.exifinterface` (pulled transitively)

## Credits & history

PixelDiet is an honest, modernized **fork**. The compression *strategy* is not original work — it
originates in:

- **[Luban](https://github.com/Curzibn/Luban)** by Zheng Zibin (Curzibn) — Copyright 2016, Apache 2.0
- **[AdvancedLuban](https://github.com/shaohui10086/AdvancedLuban)** by shaohui10086 — Copyright 2016, Apache 2.0

PixelDiet reimplements that strategy in Kotlin, removes RxJava, fixes scoped-storage and EXIF
handling, adds WebP output, and guarantees a native-free artifact. See [`NOTICE`](NOTICE) for the
full attribution and list of changes.

## License

[Apache License 2.0](LICENSE). © 2026 Basheer, with portions © 2016 Zheng Zibin and © 2016
shaohui10086.
