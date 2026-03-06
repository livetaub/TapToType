---
description: Build a release AAB for Google Play Store upload
---

## Prerequisites

- Java/JDK must be available (Android Studio's bundled JBR works)
- `keystore.properties` must exist in the project root with signing credentials
- `taptotype-release-key.jks` must exist in the project root

## Steps

// turbo-all

1. Verify the version in `version.properties` matches what you want to release:
   ```powershell
   Get-Content "version.properties"
   ```

2. Set JAVA_HOME to Android Studio's bundled JDK and build the release AAB:
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   cmd /c "`"c:\My Projects\TapToType\gradlew.bat`" bundleRelease --no-daemon"
   ```

3. Verify the AAB was created:
   ```powershell
   Get-ChildItem "app\build\outputs\bundle\release\*.aab" | ForEach-Object { Write-Host "$($_.Name) - $([math]::Round($_.Length/1MB, 2)) MB" }
   ```

4. The output AAB file is at:
   ```
   app/build/outputs/bundle/release/app-release.aab
   ```

## Uploading to Google Play Console

1. Go to [Google Play Console](https://play.google.com/console)
2. Select **TapToType** app
3. Go to **Release** → **Production** (or **Testing** → **Internal testing** for testing first)
4. Click **Create new release**
5. Upload the `app-release.aab` file
6. The version name and code are baked into the AAB from `version.properties`
7. Add release notes, then **Review** and **Roll out**

## Notes

- The AAB is signed with the release keystore (`taptotype-release-key.jks`)
- Google Play re-signs it with your upload key for distribution
- `versionCode` must be **higher** than the currently published version, otherwise Play Console will reject it
- Always bump the version via `/git-push` before building a release
