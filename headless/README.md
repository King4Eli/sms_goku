# headless

Minimal Android app whose only job is to send an SMS when triggered by an adb
broadcast. No UI, no launcher icon, no other code.

`SmsManager.sendTextMessage` is a stable public API, so this works unchanged
from Android 5 through 16 without root and without being the default SMS app.

## Build & install

```sh
cd headless
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.smsgoku.headless android.permission.SEND_SMS   # one time
```

`SEND_SMS` is a runtime permission (Android 6+); `pm grant` sets it without any UI.

## Send

```sh
adb shell "am broadcast -f 0x00000020 \
  -n com.smsgoku.headless/.SendSmsReceiver \
  --es number '16505551234' \
  --es message 'hello from adb'"
```

Success prints:

```
Broadcast completed: result=-1, data="sent 1 part(s)"
```

`result=-1` is `RESULT_OK`. On failure `result=0` and `data="error: ..."`.

### Flags / extras

| Token | Purpose |
|---|---|
| **outer `"` quotes** | adb starts a second shell on the device; without them the message splits on spaces |
| `-f 0x00000020` | `FLAG_INCLUDE_STOPPED_PACKAGES` — required on the first broadcast after install or reboot, because the app has no activity to bring it out of the "stopped" state |
| `--es number '...'` | destination MSISDN |
| `--es message '...'` | body; auto-split into multipart if long |
| `--ei subId <n>` | optional SIM subscription id (dual-SIM); ids from `adb shell dumpsys isub` |

## Verify

```sh
adb logcat -d -s headless
adb shell content query --uri content://sms/sent --projection address,body,date --sort '"date DESC"'
```

## Notes

- On an emulator the send goes through the RIL but the simulated network has no
  real recipient — only `result=-1` and the `content://sms/sent` row confirm it ran.
- On a real device with a SIM this sends a real, billable SMS.
- The receiver is `exported="true"` but gated by `android:permission="android.permission.SEND_SMS"`,
  so only callers that hold `SEND_SMS` (the adb `shell` user, system) can trigger it.
- Bulk sending (~30 msgs / 30 min, or premium/short-code numbers) can raise a
  system confirmation dialog — normal numbers at low volume are silent.
