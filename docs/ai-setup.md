# Install with an AI assistant

[All installation methods](https://github.com/ekkzy/softlumi/blob/main/docs/installation.en.md) · [Русский](ai-setup-ru.md)

This method needs an AI assistant with access to a local Windows terminal.
A chat without computer access can explain the commands but cannot run them.
For installation without AI, use the [one-command Windows guide in Russian](https://github.com/ekkzy/softlumi/blob/main/docs/one-command-setup-ru.md).

Download [softlumi-ai-setup-candidate.14.zip](https://github.com/ekkzy/softlumi/releases/download/v0.3.0-candidate.14-setup.2/softlumi-ai-setup-candidate.14.zip)
from the [private setup.2 prerelease](https://github.com/ekkzy/softlumi/releases/tag/v0.3.0-candidate.14-setup.2)
and extract it into a separate local folder. Repository access is required;
do not give an assistant your GitHub password or token. The app inside is
**softlumi 0.3.0-candidate.14 / code 35**. EVE-BOX is the new project name;
the installed app label, package and signing key remain unchanged in this release.

## Prepare

Use Windows PowerShell 5.1 or PowerShell 7, trusted Android SDK Platform-Tools
(`adb`), Build-Tools (`apksigner`, `aapt`) and Java for `apksigner`. AI mode uses
the SDK verification path. The Windows wizard instead accepts only the pinned
APK for this release and does not need Build-Tools.

Official tools: [Platform-Tools](https://developer.android.com/tools/releases/platform-tools),
[Build-Tools](https://developer.android.com/tools/releases/build-tools),
[apksigner](https://developer.android.com/tools/apksigner).
Prepare missing tools before continuing. Computing a hash of an unknown file
does not replace checking its source and signature.

The owner enables debugging, accepts authorization on the TV and completes
code pairing if needed. `setup-tv.ps1` does not run `connect`, `pair` or `tcpip`.
The assistant must select an explicit ADB target and read its model and build
fingerprint, rather than reuse an address from an old journal.

Open the kit folder in the assistant and give it the
[installation prompt in Russian](installation-prompt-ru.md), or ask it to follow
this guide. Installation and opening the app are the defaults. Additional
changes must appear in the plan and receive your approval first.

## Create a plan

The ZIP metadata is `manifest.json`; its repository copy is
[`distribution/ai-setup/manifest.json`](https://github.com/ekkzy/softlumi/blob/main/distribution/ai-setup/manifest.json).
Check the source and files against the trusted release and
[SHA256SUMS.txt](https://github.com/ekkzy/softlumi/releases/download/v0.3.0-candidate.14-setup.2/SHA256SUMS.txt).
The installer verifies APK SHA-256, certificate, package, Activity, version and
Android compatibility. The package is `io.github.ekkzy.softlumi`.

Example for the assistant, from the extracted ZIP root. Replace paths, model
and fingerprint with the values for the intended device:

```powershell
$setupTarget = @{
    Serial = '<explicitly selected serial>'
    ExpectedModel = '<selected TV model>'
    ExpectedFingerprint = '<selected TV build fingerprint>'
    AdbPath = 'C:\Android\Sdk\platform-tools\adb.exe'
}
$setupManifest = Get-Content .\manifest.json -Raw -Encoding UTF8 | ConvertFrom-Json
New-Item -ItemType Directory -Path .\setup-private -Force | Out-Null
$setupPlan = '.\setup-private\plan-01.json'

.\scripts\setup-tv.ps1 -Mode Plan @setupTarget `
    -Apk $setupManifest.apk.file -ApkSha256 $setupManifest.apk.sha256 `
    -SigningCertSha256 $setupManifest.apk.signingCertSha256 `
    -ApkSignerPath 'C:\Android\Sdk\build-tools\35.0.0\apksigner.bat' `
    -AaptPath 'C:\Android\Sdk\build-tools\35.0.0\aapt.exe' -PlanPath $setupPlan

$setupApprovedSha = (Get-FileHash $setupPlan -Algorithm SHA256).Hash.ToLowerInvariant()
```

`Plan` reads the TV and saves a local file; it makes no TV changes. The assistant
shows the target, APK, previous and requested HOME, every selected package and
unchanged actions. Approval applies to that exact file and SHA-256. Do not
change the plan and silently recalculate its approved hash. Plans expire after
30 minutes; a new or expired plan needs another review. Use new plan and
journal filenames for each operation.

Only after your approval:

```powershell
.\scripts\setup-tv.ps1 -Mode Apply @setupTarget -PlanPath $setupPlan `
    -ApprovedPlanSha256 $setupApprovedSha -JournalPath .\setup-private\apply-01.json

.\scripts\setup-tv.ps1 -Mode Inspect @setupTarget `
    -JournalPath .\setup-private\apply-01.json
```

`Apply` installs while retaining app data, verifies the installed APK version
and SHA-256 on the device, opens softlumi and checks its foreground Activity.
It then performs approved package and HOME changes. Downgrades are refused.
`Inspect` reads the state without repairing it or rewriting the journal.

## HOME and apps

The default `generic-install-only` profile leaves HOME and packages unchanged.
Additional changes require the exact firmware in the
[Mi Box S 3 profile](../scripts/setup-profiles/mi-box-s3-android14.json):
model `MiTV-AFMU0`, API 34 and fingerprint
`Xiaomi/twilight/twilight:14/UKG3.250826.001/V816.0.11.0.UZFAABX:user/release-keys`.
Other models or builds get installation-only. Do not edit the profile to
bypass this check.

Explicit options for **Plan**:

- `-ProfileId mi-box-s3-android14 -SetHome` selects softlumi as HOME.
- `-DisableStockHome` also disables Google TV Home and requires `-SetHome`.
- `-DisablePackages` takes an array of selected exact package names from the profile.

Choices include Netflix, Prime Video, YouTube Music, Google Play Games,
Mi Music, Xiaomi analytics and Google Feedback. All are off by default.
A disabled app cannot launch for Android user 0, but its data remains.
Packages already in state 2, 3 or 4 retain that exact state. A missing selected
package stops planning or applying. A failure during execution can leave
a partial result in the journal.

This does not copy every disabled package from another box or promise to remove
all ads or telemetry or improve performance. Remote services, old HOME
redirectors, accessibility, accounts, sleep, screensaver, CEC and voice settings
are not configured. A conflicting old service needs separate review rather
than disabling all accessibility services.

## Restore or handle an error

Run `Inspect` first, then, when appropriate:

```powershell
.\scripts\setup-tv.ps1 -Mode Restore @setupTarget `
    -JournalPath .\setup-private\apply-01.json
```

`Restore` returns only recorded HOME and package changes, including a partially
applied plan. It restores the old HOME package before choosing its Activity.
The installed APK and app data remain; the previous APK is not reinstalled.
Use the same target and unchanged ADB tool. A journal older than 14 days,
changed firmware or subsequent state changes require review.

For an unknown installation outcome, `Inspect` reports
`installOutcomeUnknown: true`; the journal may say `NeedsReconcile`. Even
an observed APK version and hash do not authorize an automatic retry.
`Restore` also refuses that state. First establish that the Android install
session has ended, or resolve that uncertainty separately. Do not uninstall,
clear data or downgrade to bypass an error.

If the response to disabling stock HOME was lost, restoration may be partial:
`Restore` can first return its affected HOME package but refuse to overwrite
an unknown third-party HOME choice. Read the journal before continuing;
do not edit it to bypass checks.

Check the physical Home button, Back, app launches and favorites. A complete
run of the new kit on a factory-configured physical Mi Box S 3 is not yet
confirmed. Installation does not automatically grant viewing-history access.
Plans and journals contain target identity, firmware, HOME and package states.
Keep `setup-private` local and do not publish these files.
