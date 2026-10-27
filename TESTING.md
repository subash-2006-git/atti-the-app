# Two-phone test checklist

Run this before tagging a release. Install the **release** APK on both phones
(`app/build/outputs/apk/release/app-release.apk`). The second phone can install
it from a file shared over WhatsApp, Drive or Nearby Share — no USB debugging needed.

Phone A = your phone, Phone B = the borrowed phone. Tick each item.

## First run
- [ ] A and B: name screen appears; a blank name shows an error; 20 characters is the limit
- [ ] A and B: permission prompt appears once, all permissions in one go
- [ ] Deny on one phone → explanation with **Try again**; Try again → prompt again
- [ ] With Bluetooth off → "Turn on Bluetooth" screen; turning it on continues to scan

## Finding each other
- [ ] Both open ATTI → each sees the other's name within ~10 s
- [ ] Close ATTI on B → after a while B disappears from A's list (or Rescan clears it)
- [ ] Only one phone open for 15 s → "No one nearby yet" + Rescan

## Requests
- [ ] A taps B → A sees "Waiting for B"; B sees "A wants to chat"
- [ ] B taps **Decline** → A sees "B declined your request" and is back on the list
- [ ] A taps B, then **Cancel request** → B's popup disappears
- [ ] A taps B and B does nothing → after 45 s A sees "B didn't answer"
- [ ] B taps **Accept** → both phones open the chat, top bar says Connected
- [ ] *(needs a 3rd phone C)* while A and B chat, C taps A → C sees "A is busy in another chat"

## Chat
- [ ] Messages arrive both ways; yours on the right, theirs on the left, with the time
- [ ] Long message (a few hundred characters) arrives whole
- [ ] Emoji and Tamil text arrive correctly
- [ ] Rotate the phone → chat and draft stay
- [ ] Turn off mobile data and Wi-Fi internet / airplane mode with Bluetooth on → chat still works

## Photos
- [ ] Photo button opens the photo picker; picking a photo sends it
- [ ] Sender sees the photo dimmed with a progress ring, then clear
- [ ] Receiver sees it appear on the left, upright and not stretched
- [ ] A real camera photo (several MB, taken sideways) arrives upright within a few seconds
- [ ] Tap a photo → full screen; pinch and double-tap zoom; Back closes it
- [ ] Walk out of range while a big photo is sending → it shows "Not sent" / "Couldn't receive"
- [ ] Leave the chat → photos are gone (they are never saved)

## Privacy
- [ ] Screenshot in any ATTI screen (scan, chat, photo viewer, request popup) → blocked or black
- [ ] Screen recording while ATTI is open → ATTI shows black
- [ ] Recent apps → ATTI's card is blank

## Files
- [ ] 📎 opens the file picker; any file type can be picked
- [ ] Sender sees name, size and a progress bar ("1.2 of 4.8 MB"); receiver sees the same while it arrives
- [ ] Tap a finished file → it opens in a matching app (e.g. a PDF viewer)
- [ ] A file type with no app → hint to save it instead
- [ ] Received file → Save → choose a folder → "Saved <name>"; the file is there afterwards
- [ ] A file over 100 MB is refused with a message
- [ ] Out of range mid-transfer → "Not sent" / "Couldn't receive"
- [ ] Leave the chat → unsaved files are gone

## Typing and voice messages
- [ ] A types → B sees "typing…" under A's name and a dots bubble; it goes when A sends, clears the box, or stops for 5 s
- [ ] First tap on the mic asks for the microphone; refusing shows a hint and doesn't record
- [ ] Recording shows a timer and live waveform; B sees "recording audio…"
- [ ] Cancel throws the recording away; a recording under a second isn't sent
- [ ] Send → A's voice message shows a progress ring, then a play button; B receives it
- [ ] Play: timer counts and the waveform fills; Pause and resume work; only one plays at a time
- [ ] Going to the home screen mid-recording drops the recording
- [ ] Leave the chat → voice messages are gone

## Disconnects
- [ ] B presses Back → A immediately sees "B left the chat", Send is disabled, messages stay
- [ ] A presses Back → back on the scan list, can find B again
- [ ] Walk away until out of range → "Disconnected – out of range" banner (may take up to ~30 s)
- [ ] Turn off Bluetooth on B mid-chat → A shows the banner
- [ ] Swipe ATTI away on B mid-chat → A shows the banner

## Result
Record the date, both phone models and Android versions, and anything that failed.

### 2026-10-21 — 1.0.0-rc.2 (release build)
Phones: moto g64 5G (Android 16) and vivo I2219 (Android 16), driven over adb.

| Check | Result |
|---|---|
| Blank name error, 20-character limit | Pass |
| Wi-Fi off → "Turn on Wi-Fi" screen | Pass |
| Each phone finds the other | Pass (failed in rc.1: missing location permission — fixed) |
| Request → Decline → "declined" message | Pass |
| Cancel request → other phone's popup closes | Pass |
| Accept → both open the chat, Connected | Pass, in both directions |
| Messages both ways, with times | Pass |
| Long message (~1,000 characters) arrives whole | Pass (end was cut off on screen in rc.1 — fixed) |
| Back → other side sees "left the chat" | Pass, within 2 s |
| Bluetooth off mid-chat → out-of-range banner | Pass, both phones, ~22 s |
| App force-closed mid-chat → banner on the other phone | Pass, ~6 s |
| No answer → "didn't answer" after 45 s, popup closes | Pass, 47 s |
| Refusing permissions → Try again → refuse again → Open Settings → grant → scan resumes | Pass (vivo, fresh install; granted from adb rather than by tapping in Settings) |
| Rotation mid-chat: messages, Connected status and unsent draft kept; send in landscape | Pass |
| Tamil and emoji both ways (வணக்கம் 🥰🐱 😹) | Pass, typed by hand |
| Walking out of range → banner on both phones | Pass, at roughly 50–75 m; Moto noticed ~1.5 min after the walk began |
| Busy (needs a 3rd phone) | Not tested; covered by Busy Bot in debug builds |
| Install from Downloads and chat with mobile data / internet off | Pass, tested by hand by the owner |

Known issue: twice, right after ATTI was reinstalled on one phone, the first
request didn't reach the other phone and left no trace in the logs. Tapping the
name again worked both times.

### 2026-10-21 — 1.1.0 photo sharing (debug build)
Phones: moto g64 5G and vivo I2219 (Android 16), driven over adb with two
generated test pictures (3000×2000 landscape, 1200×2400 portrait).

| Check | Result |
|---|---|
| Photo button opens the picker; picking sends | Pass on both phones (the vivo picker needs **Done** after ticking) |
| Sender sees dimmed photo with progress ring, then clear | Pass (Test Bot) |
| Moto → vivo, landscape | Pass, under 1 s, upright, not stretched |
| vivo → Moto, portrait | Pass, upright |
| Full-screen viewer, double-tap zoom, Back closes | Pass |
| Photos deleted after leaving the chat | Pass, 0 files left in the app cache |
| Real camera photo taken sideways | Not tested yet |
| Out of range while a photo is sending | Not tested yet |

### 2026-10-22 — 1.2.0-beta.1 typing and voice (debug build, one phone)
Phone: moto g64 5G (Android 16) with Test Bot. Released as a beta before
two-phone testing, at the owner's request; the owner will test by hand.

| Check | Result |
|---|---|
| "typing…" in the top bar and dots bubble; cleared when the reply arrives | Pass (Test Bot) |
| First mic tap asks for the microphone; "Don't allow" shows a hint, no recording | Pass |
| Recording bar: timer, live waveform, Send | Pass |
| Voice message sent; Test Bot sends one back | Pass |
| Play: timer counts and waveform fills | Pass |
| Pause and resume | Not tested |
| Typing and voice between two real phones | Not tested |
| "Turn on Bluetooth" screen moves on when Bluetooth is switched on in the background | Pass |
