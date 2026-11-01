# Imported Android development skills

Imported from [GuillemRoca/agent-skills-android](https://github.com/GuillemRoca/agent-skills-android) (MIT License, copyright 2026 Guillem Roca).

Included:
- `android-ui-engineering`: Compose UI architecture, state, adaptive layouts, animations, accessibility, previews and screenshot verification.
- `incremental-implementation`: small working slices with build/test verification.
- `test-driven-development`: Red-Green-Refactor and regression test practice.

These are guidance documents, not runtime dependencies. They are stored in `.agent/skills/` following the Android project-local agent-skill convention. Review each recommendation against this app's existing Compose/Gradle versions; do not mechanically migrate Navigation or architecture as part of importing the skills.

Google's official Android skill collection is also documented at https://github.com/android/skills and https://developer.android.com/tools/agents/android-skills. Use it as an additional reference for API-specific tasks.

## Official Android skills (Apache-2.0)

Imported from [android/skills](https://github.com/android/skills), Google's official Android agent-skills repository:
- `jetpack-compose-adaptive`: adaptive Compose layouts and large-screen/window-size behaviour.
- `jetpack-compose-performance`: Compose runtime and rendering performance.
- `android-testing-setup`: Android testing environment and verification practices.

See `LICENSE-APACHE-2.0.txt`. Source skills are preserved with their original content and license. Review current SDK/library compatibility before applying API-specific recommendations.
