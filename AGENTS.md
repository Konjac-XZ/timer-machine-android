# Repository Guidelines

## Project Structure & Module Organization
- `app` aggregates feature modules and hosts flavors (`dog`, `google`, `other`).
- Feature flows live in `app-*/` modules; shared UI and services sit in `component-*`.
- `presentation/` holds Hilt ViewModels powered by `domain/` use cases and `data/` repositories.
- Gradle conventions live in `build-logic/`; baseline profiles are versioned in `baselineProfile/`.
- Tests mirror code: modules keep `src/test`, while UI specs stay in `app/src/androidTest`.

## Build, Test, and Development Commands
- `./gradlew assembleDogDebug` builds the dog-flavor dev APK.
- `./gradlew :app:installDogDebug` installs the dog build on a device.
- `./gradlew lintDogDebug detekt` runs Android Lint plus detekt.
- `./gradlew testDogDebugUnitTest` runs JVM unit tests across modules.
- `./gradlew connectedDogDebugAndroidTest` runs Espresso UI tests.
- `./gradlew generateBaselineProfile` regenerates the baseline profile set.
- The compile toolchain is bundled with Android Studio, typically under `C:\Program Files\Android\Android Studio\jbr`; `JAVA_HOME` and `PATH` may not be preconfigured.

## Coding Style & Naming Conventions
- Use Kotlin 4-space indentation; keep trailing commas and expression bodies when they clarify intent.
- Follow detekt (`detekt-config.yml`); add suppressions only with written rationale.
- Types use PascalCase, members camelCase, and flavor resources live under `src/<flavor>`.
- Store strings, dimensions, and drawables in `app/src/main/res`; share UI helpers via extension functions.
- Inject via Hilt modules in `data`/`presentation`, never through service locators.

## Collaboration Notes
- This app is maintained primarily for personal use; prefer pragmatic, easy-to-review changes over broad rewrites.
- Explain Android and Kotlin changes clearly, assuming familiarity with older Java syntax but little Kotlin experience.
- Keep i18n coverage to English and Simplified Chinese unless explicitly requested otherwise.
- Unless the user says otherwise, assume the current branch is a messy, experimental branch and automatically commit completed changes.

## Testing Guidelines
- Name unit tests `ClassNameTest` and keep fixtures beside code in `src/test`.
- Instrumentation specs stay in `app/src/androidTest` and end with `AndroidTest`.
- Use `kotlinx.coroutines.test.runTest` for suspend APIs and Mockito fakes for Hilt bindings.
- Add regression coverage for new domain or presentation logic before a PR.
- Refresh the Baseline Profile for performance work and quote macrobenchmark or profiler numbers.

## Commit & Pull Request Guidelines
- Use Conventional Commit messages, such as `fix(tts): handle partial cloud subtitle batches`.
- Rebase or squash so each commit is focused and free of generated files.
- Reference issues (`Fixes #123`) and note impacted flavors or modules.
- Attach UI screenshots or recordings and list manual validation steps.
- Ensure CI (`android.yml`) is green; call out follow-up chores in the PR body.

## Configuration & Secrets
- Keep signing keys and App Center secrets in `local.properties`; never commit them.
- Place `google-services.json` only for the `google` flavor and keep it untracked.
- Use `fastlane/` for store automation; provide secrets via environment variables when running lanes.
- Maintain optional Firebase cleanup logic in `functions/`; deploy it only for the Play flavor with Node 18.
