# Repository instructions

- Work directly on `main`; never create branches or pull requests.
- Commit and push completed requested changes to `origin/main`, unless explicitly told not to.
- This is a native Android Kotlin/Compose app. MCP, LLM, Python embedding, and cloud work are out of scope.
- The Activity and ViewModel must never own physical drone sockets or sessions.
- The foreground service/session owns real Tello connectivity.
- Preserve explicit Android Network socket binding.
- Preserve RC TTL/stale-input zeroing, STOP/HOVER, Emergency separation, and conservative fail-closed flight state.
- Never begin the next roadmap phase unless explicitly requested.
- Project targets: `minSdk 28`, `compileSdk 37`, `targetSdk 37`.
- Use Android Studio JBR 25.
- If the Unicode Windows checkout breaks Gradle test workers, verify from the established ASCII-path copy; do not change application code to work around that path issue.

## Verification tiers

- Tier 0: `git diff --check`
- Tier 1: focused relevant tests + `git diff --check`
- Tier 2: focused tests + `assembleDebug` + `git diff --check`
- Tier 3: `test` + `assembleDebug` + `lintDebug` + `git diff --check`
- Tier 4: full relevant release/checkpoint verification

Use the lowest tier justified by the task; do not force a higher tier without a reason.
