# AGENTS

## Mission

Build the MVP: scan -> list -> tap -> history.

## Hard constraints

- No Android Studio usage (everything must work via CLI + VS Code)
- Reproducible environment setup (script + docs)
- No cloud; no remote logging by default

## Toolchain rules

- Use Android SDK Command-line Tools (sdkmanager, platform-tools, etc.)
- Require JDK 17
- Pin AGP/Gradle versions using the official matrix; avoid dynamic versions

## Definition of Done (MVP)

- On a physical Android device: can scan, see list, tap a device, see a persisted history after relaunch
- Permission handling is robust (clear messaging, recoverable states)
- No crashes when Bluetooth is off or permissions denied

## Implementation guidance

- Treat observed device identifiers as observations, not ground truth identity
- Assume devices may rotate addresses and change names; design for partial stability
- Optimize for battery by time-limiting scans and avoiding constant background behavior (MVP stays foreground)

## PR discipline

- Small PRs, each linked to TODO item
- Add or update docs with every new requirement
- Commit and push after every task; if a push is blocked, surface the blocker immediately
- Track active multi-step work in `TODO.md` and update the checklist before or during implementation as scope changes
- For long-running work, commit and push each completed sub-task even when the larger feature is still in progress

## Recursive learning

- Update `AGENTS.md` whenever you learn anything important about the project, workflow, or collaborator preferences
- Capture both wins and misses: what to repeat, what to avoid, and any blocker that slowed delivery
- Keep notes concrete and reusable: build/test commands, deployment steps, project structure, coding conventions, pitfalls, and formatting preferences
- Prefer small, timely updates in the same task that revealed the learning, and replace stale guidance when it is superseded

## Agent memory checklist

- Build/test: `./gradlew assembleDebug`, `./gradlew installDebug`, and `scripts/stage-apk`
- Deployment: GitHub Pages publishes from `main` at repo root to `https://unagi.ninja`
- Site artifacts: keep `index.html`, `downloads/unagi-debug.apk`, and `CNAME` aligned when shipping landing-page changes
- Reflection: before handoff, record any new command, pitfall, deploy detail, or collaborator preference discovered during the task
