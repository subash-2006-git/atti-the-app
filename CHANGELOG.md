# Changelog

All notable changes to ATTI are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/).

## 1.1.0 - Unreleased

### Added
- Photo sharing, Instagram DM style: a photo button in the message box opens
  Android's photo picker; photos are shrunk before sending (long side up to
  1600 px), appear at once with a progress ring, and open full-screen with
  pinch and double-tap zoom. Like messages, photos are never saved — they are
  deleted when the chat ends.

## 1.0.0 - 2026-10-21

First version: offline one-to-one text chat between nearby phones.

### Added
- Name screen on first launch (1–20 characters), saved on the phone.
- Permission handling for Android 8 to 16, with "Try again" after a refusal and
  a link to Settings after "Don't ask again". Prompts to turn on Bluetooth,
  Wi-Fi and Location. Checks for Google Play Services.
- Scan screen: searching loader, list of nearby ATTI users, and a
  "No one nearby yet" state with Rescan.
- Chat requests: the other person gets an Accept / Decline popup; the requester
  sees declined, busy, no answer, or connection failed.
- Chat screen: message bubbles with times, Connected / Disconnected status,
  and a banner when the other person leaves or goes out of range.
- Debug builds: Test Bot, Shy Bot and Busy Bot for testing with one phone.

### Fixed since 1.0.0-rc.1
- Phones could not find each other on Android 13+: Nearby Connections needs the
  location permission on every version. ATTI now asks for it (approximate is
  enough) and explains why.
- The end of a long message was cut off in the chat; newest messages now sit
  at the bottom and are always fully visible.
