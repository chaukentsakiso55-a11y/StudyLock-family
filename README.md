# StudyLock Family

Two native Android apps for student focus and trusted family support, developed under Cyber Pulse.

## Modules

- `student`: focus timer, study workspace, local progress and future tutor/pairing surfaces
- `parent`: family goals, schedule/command workspace and future progress views
- `shared`: pairing, metrics and command contracts used by both apps

## What works in this foundation

- Two independently installable Kotlin + Jetpack Compose apps
- Dark neon liquid-glass interface
- Local focus timer with the 25-minute to 5-hour product boundary
- Local notes, progress, preferences and data clearing
- Six-digit expiring pairing-code model
- Typed family commands for starting/ending focus, blocked apps and schedules
- Unit tests and GitHub Actions builds for both APKs

## Firebase phase

Remote pairing and controls are deliberately not simulated. They require authenticated Firebase users, security rules, App Check and an auditable command path. The shared models in this repository define the boundary for that work.

## Open in Android Studio

1. Open the repository root.
2. Select JDK 17 and allow Gradle sync to finish.
3. Run either the `student` or `parent` configuration on Android 8.0 or newer.

The **Android family build** workflow builds both debug APKs.

Cyber Pulse · Foundation version 0.1.0
