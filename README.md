# Jot

Jot is a native Android app for a local-only daily check-in journal. Each entry records the date, mood, nap, workout and free-form notes.

All data stays on the device. The app does not request the network permission. A server/sync backend is planned for a later stage, which is why the app lives in the `android/` subdirectory.

## Install

Install with [Obtainium](https://github.com/ImranR98/Obtainium): add `https://github.com/BackSlasher/jot` as an app source. It picks up the APK from GitHub Releases.

Release APKs are signed with a key whose certificate SHA-256 fingerprint is:

```
02:3D:37:78:8F:90:57:57:48:7C:46:40:31:ED:D2:58:7E:6E:79:80:50:3B:A3:FC:13:F1:54:6A:64:EF:9A:BA
```

To check a downloaded APK: `apksigner verify --print-certs jot-X.Y.Z.apk`.

## Build locally

The only requirement is podman. From the repo root:

```
./build.sh                  # unit tests + debug APK
./build.sh <gradle tasks>   # arguments are passed through to Gradle
```

## Release

Push a tag of the form `vX.Y.Z`:

```
git tag v1.2.3 && git push origin v1.2.3
```

The Release workflow runs the tests, builds and signs the APK, and publishes a GitHub Release with `jot-X.Y.Z.apk` attached. The version name is the tag without the `v`; the version code is `major*10000 + minor*100 + patch` (minor and patch must be at most 99).
