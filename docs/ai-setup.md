# Install softlumi with an AI assistant

The kit lets an AI assistant with access to a local Windows terminal verify the APK, prepare an installation plan, and apply it after your approval. A chat without computer access can explain these steps but cannot run them. Windows PowerShell 5.1 and PowerShell 7 are supported.

This is an experimental installer from a [private prerelease](https://github.com/ekkzy/softlumi/releases/tag/v0.3.0-candidate.13-setup.1). Downloading it requires access to the private repository. The Xiaomi profile has not completed an end-to-end check on a freshly configured device. Offline automated tests do not establish that compatibility.

## Get started

1. Download [softlumi-ai-setup-candidate.13.zip](https://github.com/ekkzy/softlumi/releases/download/v0.3.0-candidate.13-setup.1/softlumi-ai-setup-candidate.13.zip) from the release and extract it into a separate folder. Do not give an assistant your GitHub password or access token.
2. Open that folder in an AI assistant with a local terminal. Give it the [installation prompt in Russian](installation-prompt-ru.md), or ask it to follow this guide. It must show a concrete plan for the selected device first.
3. Approve only a plan you understand. By default, the kit installs and opens softlumi; it preserves the current HOME, other apps, and system settings.

You need trusted Android SDK Platform-Tools (`adb`), Build-Tools (`apksigner`, `aapt`), and Java for `apksigner`. Replace the example paths below with your installed tools. If they are missing, the assistant should explain how to obtain official Android SDK tools before continuing. Official references: [Platform-Tools](https://developer.android.com/tools/releases/platform-tools), [ADB connection](https://developer.android.com/tools/adb), and [APK signature verification](https://developer.android.com/tools/apksigner).

You enable developer options and debugging, accept the RSA prompt on the TV, and complete wireless pairing when the firmware supports it. The installer does not run `connect`, `pair`, or `tcpip`. Select the device explicitly. An old IP address, `ANDROID_SERIAL`, or a single entry in the device list does not establish which device you intended to change.

## Available choices

The default `generic-install-only` profile installs and opens softlumi without changing HOME or disabling packages.

The `mi-box-s3-android14` profile allows additional actions only when model, API, and fingerprint exactly match `scripts/setup-profiles/mi-box-s3-android14.json`: model `MiTV-AFMU0`, API 34, and the specified Xiaomi Android 14 build. The product name “Mi Box S 3” alone is insufficient. Other firmware is refused by this profile; installation-only remains available. Do not edit the profile to bypass this check.

All additional actions are off by default:

| Choice | Effect |
| --- | --- |
| Make softlumi the HOME app | Requests a transition to softlumi. The physical Home button still needs a separate check. |
| Disable Google TV Home | Makes the stock launcher unavailable to Android user 0. Requires a HOME transition; its previous state is recorded. |
| Netflix, Prime Video, YouTube Music, Mi Music | Makes the selected app unavailable to Android user 0. Choose only apps you do not use. |
| Google Play Games | Makes its game discovery and profile interface unavailable. This does not promise to disable every gaming service. |
| Xiaomi analytics | Disables one specified Xiaomi package. Other apps may still send telemetry; effects on a freshly configured device have not been verified. |
| Google Feedback | Feedback and diagnostic reports using this package may become unavailable. Other reporting mechanisms are separate. |

This is a limited opt-in list. It does not copy every disabled package from another device or promise to remove all advertising, save memory, or improve frame rates. A missing selected package stops the plan rather than silently applying only part of it. An already disabled package is recorded without changing its state.

Existing launcher services and button mappings need separate attention. For example, a remaining Free My TV service can redirect Google TV Home, while another service handles branded buttons. The kit neither disables nor configures them. Stop and inspect a conflict while preserving their previous state. Setup Wizard, storage, media services, and OEM HOME companion packages are excluded. Screensaver, sleep, CEC, and voice settings are outside this plan.

## Verify the kit and create a plan

Run commands from the extracted ZIP root. Its metadata file is `manifest.json`; the repository copy is [`distribution/ai-setup/manifest.json`](https://github.com/ekkzy/softlumi/blob/main/distribution/ai-setup/manifest.json). First compare the kit with the trusted publisher page and repository metadata. A hash calculated from an unknown downloaded file does not establish who published it.

The manifest records kit file hashes, APK and certificate SHA-256, package, and version. The installer checks the APK using `apksigner` and `aapt`. The expected package is `io.github.ekkzy.softlumi`. A certificate, version, or installed-app conflict needs investigation; uninstalling an old APK or clearing data is not an automatic remedy.

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

`Plan` reads the selected TV through ADB and writes a local plan; it does not apply TV changes. The assistant should show the device, APK, previous and requested HOME, every selected package, unchanged actions, and restoration limits. Before `Apply`, approve the exact file shown and its SHA-256. Do not change the plan and silently recalculate the approved hash. Plans expire after 30 minutes; a new or expired plan needs another review.

To request HOME, add `-ProfileId mi-box-s3-android14 -SetHome` to **Plan**. Disabling the stock launcher also requires `-DisableStockHome`. Optional apps use `-DisablePackages`, an array of exact package names from the profile. None of these options are needed for installation-only.

After reviewing and approving the plan:

```powershell
.\scripts\setup-tv.ps1 -Mode Apply @setupTarget -PlanPath $setupPlan `
    -ApprovedPlanSha256 $setupApprovedSha -JournalPath .\setup-private\apply-01.json

.\scripts\setup-tv.ps1 -Mode Inspect @setupTarget `
    -JournalPath .\setup-private\apply-01.json
```

Use new plan and journal filenames for each operation. `Apply` installs and verifies the APK, opens softlumi, and confirms it is in the foreground before selected package and HOME changes. `Inspect` reads the actual state; it does not repair it or rewrite the journal.

## Check the result

Check the physical Home button, Back, app launches, favorites, and their order. Check voice search, Cast, or remapped buttons separately if you use them. Android's `Success` response and a HOME role holder do not establish physical remote behavior.

“Continue watching” depends on real Android access and providers. Installation does not automatically grant access to viewing history, and demo cards must not replace personal data. System screensaver and sleep timers are preserved; their delayed behavior is unverified until observed.

## Restore or handle an error

Read `Inspect` and the journal first. When appropriate, run:

```powershell
.\scripts\setup-tv.ps1 -Mode Restore @setupTarget `
    -JournalPath .\setup-private\apply-01.json
```

`Restore` uses the recorded previous HOME and package states, including a partially applied plan. It retains the installed softlumi APK and app data. It does not reinstall an older APK or enable every stock app. Automatic restoration is limited to journals no older than 14 days; a changed firmware or ADB tool also requires separate review. If another tool or the user has since changed the state, inspect the conflict rather than editing the journal.

If ADB loses the response after disabling the stock HOME package, `Inspect` may report `homeRestoreDeferredUntilOldPackage: true`. `Restore` then restores that affected HOME package's previous state first. If Android still selects an unknown third-party HOME, restoration stops with an error without changing that selection. This is a partial result: the stock package may already be restored while other steps remain unfinished. Read the journal and resolve the conflict before continuing.

After a timeout or lost connection, the APK installation outcome may be unknown. `Inspect` reports `installOutcomeUnknown: true`; the journal may have status `NeedsReconcile`. An observed APK version and SHA do not clear this uncertainty or authorize an automatic retry. `Restore` refuses with `install_outcome_unknown_no_automatic_retry`. Package and HOME changes have not started, so there is no profile configuration to restore yet. First establish that the Android installation session has finished, or resolve that session separately, then create a new plan. Do not uninstall, clear data, or downgrade to bypass an error.

Plans and journals include device identity, build, HOME, and package state. Keep `setup-private` local. Do not publish these files, viewing history, accounts, addresses, or diagnostic logs. The assistant's final report should distinguish completed checks, errors, and unverified behavior.
