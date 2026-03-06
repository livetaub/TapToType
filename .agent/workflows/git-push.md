---
description: Git commit and push workflow
---

## Rules

1. **Never push to git automatically.** Only commit and push after the user explicitly confirms the changes are accepted and ready to push.
2. When changes are ready for review, summarize what was changed and ask the user if they want to push.
3. Use separate `git add` and `git commit` commands (PowerShell doesn't support `&&`).
4. Keep commit messages concise and descriptive.

## Version Bump (Before Every Push)

Before committing, **always** auto-increment the version in `version.properties` (project root):

1. Read the current `version.properties` file which contains:
   ```
   VERSION_CODE=<integer>
   VERSION_NAME=<major>.<minor>.<patch>
   ```

2. Increment **both** values:
   - `VERSION_CODE`: increment by 1 (e.g. 2 → 3). This is the integer Google Play uses to determine update ordering.
   - `VERSION_NAME`: increment the **patch** number by 1 (e.g. `1.0.2` → `1.0.3`). This is the human-readable version shown to users.

3. Write the updated values back to `version.properties`.

// turbo
4. Use PowerShell to perform the bump. Example:
   ```powershell
   $props = Get-Content "version.properties" -Raw
   $code = [int]([regex]::Match($props, 'VERSION_CODE=(\d+)').Groups[1].Value)
   $name = [regex]::Match($props, 'VERSION_NAME=(.+)').Groups[1].Value.Trim()
   $parts = $name.Split('.')
   $parts[2] = [string]([int]$parts[2] + 1)
   $newName = $parts -join '.'
   $newCode = $code + 1
   $newContent = "VERSION_CODE=$newCode`nVERSION_NAME=$newName`n"
   Set-Content "version.properties" $newContent -NoNewline
   Write-Host "Version bumped: $code -> $newCode, $name -> $newName"
   ```

5. Include `version.properties` in the `git add` so the version bump is part of the commit.

### How It Works

- **`version.properties`** is the single source of truth for the app version.
- **`app/build.gradle.kts`** reads from `version.properties` at build time, so both the Google Play Store version (`versionCode`/`versionName`) and the in-app display (Settings → About → "Version X.Y.Z") are always in sync.
- The in-app version display (`SettingsActivity.kt`) reads the version dynamically from `packageManager.getPackageInfo()`, so no Kotlin code changes are needed — it picks up whatever `build.gradle.kts` sets.

### When to Bump Major/Minor

- **Patch** (1.0.X): Auto-incremented on every push (default behavior).
- **Minor** (1.X.0): Bump manually when adding a notable new feature. Reset patch to 0.
- **Major** (X.0.0): Bump manually for breaking changes or major redesigns. Reset minor and patch to 0.
