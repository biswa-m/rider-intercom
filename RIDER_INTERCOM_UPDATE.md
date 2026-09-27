# Rider Intercom — foreground service update

This archive contains the complete application source/configuration from the uploaded Rider Intercom project, plus the foreground-service implementation.

## Runtime behavior

- Screen off / phone locked: intercom audio continues.
- Activity in background: intercom audio continues.
- User swipes the app task away from Recents: `IntercomAudioService.onTaskRemoved()` stops the audio service and removes the ongoing notification.
- Service uses `START_NOT_STICKY`, so Android is not asked to recreate the intercom after the service is terminated.
- Force Stop is expected to stop the service.

## Foreground notification

- Uses an ongoing low-importance notification while intercom audio is active.
- Requests `POST_NOTIFICATIONS` on Android 13+ when the user starts the intercom.
- The notification opens the Rider Intercom Activity when tapped.
- Audio is not blocked if the user declines notification permission; Android may then hide the notification from the normal notification drawer.

## Project replacement

The archive is intended to be extracted over the existing project.

The uploaded source archive did not contain these existing Git/tracked files:
- `app/.gitignore`
- `app/proguard-rules.pro`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- root `gradlew` / `gradlew.bat` (if present)

They are therefore not fabricated or overwritten. Keep those existing files from your project when replacing the source.

## Main files changed

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/bmxt/riderintercom/MainActivity.kt`
- `app/src/main/java/com/bmxt/riderintercom/MainViewModel.kt`
- `app/src/main/java/com/bmxt/riderintercom/intercom/audio/IntercomAudioService.kt`
