# Third-party notices

Checked on 2026-09-22. This inventory comes from the resolved Gradle `debugRuntimeClasspath`. License metadata was checked against each artifact POM, including inherited parent POMs. Machine-specific cache paths are not distributed.

TV Finder is authored by liaojinlong and licensed under Apache-2.0. Third-party components retain their own copyrights and licenses. See the bundled [Apache-2.0 text](licenses/Apache-2.0.txt).

## Runtime dependencies

| Component | Version | License | Project and attribution |
| --- | --- | --- | --- |
| `androidx.annotation:annotation-experimental` | 1.4.0 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `androidx.annotation:annotation-jvm` | 1.6.0 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `androidx.arch.core:core-common` | 2.2.0 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `androidx.arch.core:core-runtime` | 2.2.0 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `androidx.collection:collection` | 1.0.0 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `androidx.concurrent:concurrent-futures` | 1.1.0 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `androidx.core:core` | 1.13.1 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `androidx.interpolator:interpolator` | 1.0.0 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `androidx.lifecycle:lifecycle-common` | 2.6.2 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `androidx.lifecycle:lifecycle-runtime` | 2.6.2 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `androidx.profileinstaller:profileinstaller` | 1.3.0 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `androidx.startup:startup-runtime` | 1.1.1 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `androidx.tracing:tracing` | 1.0.0 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `androidx.versionedparcelable:versionedparcelable` | 1.1.1 | Apache-2.0 | [The Android Open Source Project](https://android.googlesource.com/platform/frameworks/support/) |
| `com.google.guava:listenablefuture` | 1.0 | Apache-2.0 | [Google Inc. and contributors](https://github.com/google/guava/tree/v26.0) |
| `org.jetbrains.kotlin:kotlin-stdlib` | 1.8.22 | Apache-2.0 | [JetBrains s.r.o. and contributors](https://github.com/JetBrains/kotlin/tree/v1.8.22) |
| `org.jetbrains.kotlin:kotlin-stdlib-common` | 1.8.22 | Apache-2.0 | [JetBrains s.r.o. and contributors](https://github.com/JetBrains/kotlin/tree/v1.8.22) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.6.4 | Apache-2.0 | [JetBrains s.r.o. and contributors](https://github.com/Kotlin/kotlinx.coroutines/tree/1.6.4) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.6.4 | Apache-2.0 | [JetBrains s.r.o. and contributors](https://github.com/Kotlin/kotlinx.coroutines/tree/1.6.4) |
| `org.jetbrains:annotations` | 13.0 | Apache-2.0 | [JetBrains s.r.o. and contributors](https://github.com/JetBrains/java-annotations) |

AndroidX Core is a direct dependency; most others are transitive. Resolved dependencies do not imply that every class is executed. Attribution names identify the upstream projects; individual source files retain their own copyright notices.

- [AndroidX source and per-file notices](https://android.googlesource.com/platform/frameworks/support/).
- [Kotlin upstream NOTICE](licenses/Kotlin-NOTICE.txt), retained verbatim from the wider Kotlin distribution. The APK does not bundle the Kotlin compiler.
- [Kotlin 1.8.22 license directory](https://github.com/JetBrains/kotlin/tree/v1.8.22/license).
- [kotlinx.coroutines 1.6.4 license](https://github.com/Kotlin/kotlinx.coroutines/blob/1.6.4/LICENSE.txt).
- [Guava v26.0 license](https://github.com/google/guava/blob/v26.0/COPYING). The `listenablefuture:1.0` POM inherits Apache-2.0 from `guava-parent:26.0-android`.
- [JetBrains annotations license](https://github.com/JetBrains/java-annotations/blob/master/LICENSE.txt).

## Build and test tools

JUnit 4.13.2 (EPL-1.0) and Hamcrest Core 1.3 (BSD-3-Clause) are test-only dependencies and are not packaged in the application APK. Gradle and Android Gradle Plugin are build tools, not application features. Sources: [JUnit 4.13.2](https://github.com/junit-team/junit4/tree/r4.13.2), [Hamcrest 1.3](https://github.com/hamcrest/JavaHamcrest/tree/hamcrest-java-1.3).

## Artwork and design references

The application logo and file-type icons are original vector artwork licensed under this project’s Apache-2.0 license. Layout and information hierarchy draw on common TV file-browser and macOS Finder interaction patterns. No ES, Finder, or Apple logo, face icon, or trademark artwork is copied. No affiliation or endorsement is implied.

System fonts and platform components are supplied by the device under their original terms. Android API references are listed in the README; external implementation code and documentation examples have not been copied verbatim into this project.
