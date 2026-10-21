# ATTI

Chat with people nearby — no internet, no mobile data, no SIM.

ATTI lets two Android phones that are close together (about 10–100 m) send text
messages to each other directly. It uses Google's
[Nearby Connections](https://developers.google.com/nearby/connections/overview),
which picks Bluetooth, BLE or Wi-Fi Direct by itself and encrypts the link.

**Flow:** enter your name → scan → see nearby ATTI users → tap one → they accept → chat.

## v1 at a glance

| | |
|---|---|
| Messages | Text (up to 1,000 characters) and photos |
| Chats | One person at a time; the other person must tap Accept |
| History | Not saved — messages and photos are deleted when the chat ends or the app closes |
| Accounts / server | None; everything stays on the two phones |
| Android | 8.0 (API 26) or newer, with Google Play Services |

## Build

Requirements: Android Studio (it bundles the JDK and Android SDK).

```sh
./gradlew assembleDebug          # debug APK: app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleRelease        # signed release APK (needs keystore.properties)
```

The debug build installs as `com.subash.atti.debug`, so it can sit next to the
release app on the same phone.

### Release signing

Release builds are signed with a key that is **not** in this repository. Create
`keystore.properties` in the project root (it is git-ignored):

```properties
storeFile=C:/path/to/atti-release.jks
storePassword=...
keyAlias=atti
keyPassword=...
```

Without it, `assembleRelease` produces an unsigned APK.

## Testing with one phone

Debug builds add three pretend users to the scan list:

| Bot | What it does |
|---|---|
| **Test Bot** | Accepts, then echoes your messages and photos. Send `/bye` to make it leave, `/lost` to make it go out of range. |
| **Shy Bot** | Declines your request. |
| **Busy Bot** | Answers that it is busy in another chat. |

The bell icon on the scan screen makes Test Bot send *you* a chat request.
Release builds contain none of this.

Real phone-to-phone testing needs two phones; see [TESTING.md](TESTING.md).
Emulators can't be used — they have no real Bluetooth or Wi-Fi Direct.

## How it works

Every phone advertises its name and discovers others at the same time, using
service ID `com.subash.atti` and the `P2P_CLUSTER` strategy. Phones exchange small
JSON packets over the link:

| Packet | Meaning |
|---|---|
| `{"t":"hi"}` | Sent by the person who accepted: the chat is open |
| `{"t":"busy"}` | Sent instead of `hi` when already in a chat |
| `{"t":"msg","text":"…","ts":…}` | A text message |
| `{"t":"img","payloadId":…,"size":…,"w":…,"h":…,"ts":…}` | A photo is coming; its JPEG follows as a Nearby stream payload with that id |
| `{"t":"bye"}` | The sender left the chat |

A phone that is already chatting briefly accepts a new request just to send
`busy`, so the requester sees "busy" rather than "declined".

## Project layout

```
app/src/main/java/com/subash/atti/
├─ MainActivity.kt, AttiApplication.kt
├─ AttiApp.kt              screen navigation, request popups
├─ data/
│   ├─ UserPrefs.kt        saved name (DataStore)
│   ├─ NearbyManager.kt    advertise, discover, connect, send, receive
│   ├─ Packet.kt           wire format
│   ├─ FakePeers.kt        debug-only test bots
│   └─ model/              Peer, Message, PeerStatus
├─ ui/                     name, permissions, scan, chat, theme
└─ util/PermissionHelper.kt
```

## Versioning

Releases follow [semantic versioning](https://semver.org) and are tagged `vX.Y.Z`.
See [CHANGELOG.md](CHANGELOG.md).

Developer: Subash S
