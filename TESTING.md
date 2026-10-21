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

## Disconnects
- [ ] B presses Back → A immediately sees "B left the chat", Send is disabled, messages stay
- [ ] A presses Back → back on the scan list, can find B again
- [ ] Walk away until out of range → "Disconnected – out of range" banner (may take up to ~30 s)
- [ ] Turn off Bluetooth on B mid-chat → A shows the banner
- [ ] Swipe ATTI away on B mid-chat → A shows the banner

## Result
Record the date, both phone models and Android versions, and anything that failed.
