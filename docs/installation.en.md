# Installation

[Русский](installation.md) · [Home](../README.en.md)

The app is named **Eve Box** on the TV: `0.3.0-candidate.15`, code 36.
Its package, `io.github.ekkzy.softlumi`, and signing key are retained for
compatible updates that preserve existing app data.

Use an Android TV 8.0+ television or box with a D-pad remote. Default Home
selection depends on the firmware. Download
the files from the [candidate.15-setup.1 prerelease](https://github.com/ekkzy/eve-box/releases/tag/v0.3.0-candidate.15-setup.1).

## Choose an installation method

| Method | Requirements | HOME and other apps |
| --- | --- | --- |
| [APK manually or through ADB](#apk-manually-or-through-adb) | The APK; ADB for command-line installation | Left unchanged |
| [AI assistant](ai-setup.md) | The same Eve Box kit, ADB and an assistant with a local terminal | Only through an approved plan and an exact device profile |
| [One Windows command](one-command-setup-ru.md) | The Eve Box kit and ADB; no AI, Java or Build-Tools | Optional, explicitly approved, for the exact supported Mi Box S 3 firmware |

AI assistance uses the same archive and wizard, without Java or SDK Build-Tools.

Update an existing Eve Box installation without uninstalling it, including
previous releases named softlumi. A compatible
update with the same package and signer keeps its app data. These methods do
not import another launcher's settings or viewing history.

## APK manually or through ADB

Download [Eve-Box-0.3.0-candidate.15.apk](https://github.com/ekkzy/eve-box/releases/download/v0.3.0-candidate.15-setup.1/Eve-Box-0.3.0-candidate.15.apk)
and compare its SHA-256 with
[SHA256SUMS.txt](https://github.com/ekkzy/eve-box/releases/download/v0.3.0-candidate.15-setup.1/SHA256SUMS.txt)
from the same trusted release. In PowerShell:

```powershell
Get-FileHash .\Eve-Box-0.3.0-candidate.15.apk -Algorithm SHA256
```

To install without a computer:

1. Transfer the APK to your TV and open it in a file manager.
2. Allow that file manager to install apps if Android asks.
3. Install the APK, then open Eve Box from the app list.

With ADB already configured, run these commands from the APK folder:

```powershell
adb devices -l
adb -s SERIAL install -r --user 0 Eve-Box-0.3.0-candidate.15.apk
```

Replace `SERIAL` with the intended target from the device list. For a network
connection, use your TV's own address and debugging port, for example:

```powershell
adb connect 192.168.1.42:5555
```

That address is an example. The owner enables debugging, accepts authorization
on the TV and completes code pairing when required. See the
[ADB documentation](https://developer.android.com/tools/adb).

The manual command keeps app data but creates no restoration journal. It does
not select HOME or disable packages. Investigate a signature or version error;
do not uninstall the app or clear its data to bypass it.

## HOME and optional cleanup

After a normal installation, select Eve Box in Android's default-app settings
if the firmware offers that choice. Keep access to the previous launcher. Use
the shared Eve Box kit if you want a recorded, reversible transition.

The wizard allows automatic HOME selection and package disabling only for an
exact match to the [Mi Box S 3 profile](../scripts/setup-profiles/mi-box-s3-android14.json):
model `MiTV-AFMU0`, API 34 and the Xiaomi build fingerprint specified there.
The product name or Android version alone is insufficient. Other firmware gets
installation-only, without automatic HOME or package changes.

All additional actions are off by default. Disabling Google TV Home requires
selecting the HOME transition. Optional apps are selected separately. “Cleanup”
means disabling selected packages for Android user 0 without deleting their
data. It does not promise to remove every ad or telemetry source. Apps that
are already disabled retain their exact previous state.

## Check and restore

The wizard saves a local plan and journal. `Inspect` reads the current state;
`Restore` returns recorded HOME and package changes when the current state
still allows a safe restore. It keeps the installed APK and app data. Follow
the [Windows guide in Russian](one-command-setup-ru.md) or
the [AI guide](ai-setup.md).

After a connection failure, a command's outcome may be unknown. Keep the journal,
inspect the state and do not retry the installation blindly. A journal is not
a backup of the previous APK. A complete run of the new kit on a factory-configured
physical Mi Box S 3 is not yet confirmed; emulator checks do not establish it.

After installation, check the physical Home button, Back, app launches and
favorites. The installers do not configure remote services, accessibility,
CEC, screensaver or sleep settings.

## First launch and moving settings

Add favorites and choose a card format and background. Weather and app
recommendations are off by default. Select a city for weather; enable
recommendations and choose the apps you want. TV card access uses a separate
Android permission where the firmware makes it available.

Settings export uses JSON, and import shows a preview. The file may include
favorites, app labels and your city. Enabled recommendation apps and Android
permissions are not transferred; choose those again on the destination device.
Keep plans, journals and personal backups private.

The [Eve Box brand kit](https://github.com/ekkzy/eve-box/releases/download/v0.3.0-candidate.15-setup.1/EVE-BOX-brand-kit.zip)
contains graphics, not an installer. To build from source, follow the
[developer guide](building.en.md).
