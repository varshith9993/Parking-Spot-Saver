# DeviceWatch — Fast & Precise Hackathon Rules

## Project Stack (Skip Discovery)
- **App:** `com.example.devicewatch` (`app/build.gradle.kts`)
- **Language:** Kotlin + Java 11 (`sourceCompatibility = JavaVersion.VERSION_11`, `targetCompatibility = JavaVersion.VERSION_11`)
- **UI:** Jetpack Compose + Material 3 (`buildFeatures.compose = true`)
- **SDK:** `minSdk = 24`, `compileSdk = 36`, `targetSdk = 36`
- **Build & Verify:** `./gradlew.bat testDebugUnitTest` (unit tests) or `./gradlew.bat assembleDebug` (APK build)

## Speed & Precision Rules (Karpathy + Ponytail)
1. **Fast Path for Small Tasks:** For bug fixes, single-file edits, or small components, edit directly without reading external `SKILL.md` files. Minimal diff, zero speculation, no extra abstractions.
2. **Surgical Changes:** Touch only what the task requires. Never refactor adjacent working code.
3. **Verify Fast:** Run the narrowest Gradle check (`testDebugUnitTest` or targeted test class) after edits.

## Project-Scoped Skills (`.agents/skills/`)
Load only the 1 skill matching the task:
- **Kotlin / Coroutines:** `kotlin-patterns`, `kotlin-coroutines-flows`, `kotlin-concurrency-expert`, `android-coroutines`
- **Compose UI / Navigation:** `compose-ui`, `jetpack-compose-performance`, `navigation-3`, `edge-to-edge`, `adaptive`
- **Architecture / Data / Network:** `android-architecture`, `android-viewmodel`, `android-data-layer`, `android-retrofit`
- **Testing / Java / Gradle / Security:** `kotlin-testing`, `java-junit`, `testing-setup`, `gradle-build-performance`, `android-permissions-security`, `android-cli`, `android-emulator-skill`

## Active Global Workflows
- **Planning / Execution:** `gsd-fast` (trivial), `gsd-quick` (small feature), `gsd-plan-phase` + `gsd-execute-phase` (multi-step)
- **Code Review:** `code-review` (CodeRabbit) or `ponytail-review`
- **UI Polish:** `impeccable` + `design-taste-frontend`
