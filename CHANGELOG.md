# Changelog

All notable changes to ATTI are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow
[Semantic Versioning](https://semver.org/).

## 1.0.0 - Unreleased

First version: offline one-to-one text chat between nearby phones.

### Added
- Name screen on first launch (1–20 characters), saved on the phone.
- Permission handling for Android 8 to 16, with "Try again" after a refusal and
  a link to Settings after "Don't ask again". Prompts to turn on Bluetooth,
  Wi-Fi and (up to Android 12L) Location. Checks for Google Play Services.
- Scan screen: searching loader, list of nearby ATTI users, and a
  "No one nearby yet" state with Rescan.
- Chat requests: the other person gets an Accept / Decline popup; the requester
  sees declined, busy, no answer, or connection failed.
- Chat screen: message bubbles with times, Connected / Disconnected status,
  and a banner when the other person leaves or goes out of range.
- Debug builds: Test Bot, Shy Bot and Busy Bot for testing with one phone.
