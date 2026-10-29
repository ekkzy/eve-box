# Eve Box: install with an AI assistant

[Installation options](https://github.com/ekkzy/eve-box/blob/main/docs/installation.en.md) · [Русский](ai-setup-ru.md)

The assistant checks the kit, helps connect your TV, and starts the same
Windows wizard used for [one-command installation](one-command-setup-ru.md).
It needs access to a local Windows terminal. A chat without computer access
can explain the steps; you will need to run the command yourself.

## Get started

1. Download [Eve-Box-setup-candidate.15.zip](https://github.com/ekkzy/eve-box/releases/download/v0.3.0-candidate.15-setup.1/Eve-Box-setup-candidate.15.zip)
   from [release candidate.15-setup.1](https://github.com/ekkzy/eve-box/releases/tag/v0.3.0-candidate.15-setup.1)
   and extract the complete ZIP into a separate, writable local folder.
2. Open that folder in your assistant and give it the
   [prompt below](#ready-to-copy-prompt).
   It can explain the steps in English; the wizard's prompts are in Russian.
3. Enable debugging on your TV and accept the authorization prompt on its
   screen; the assistant can help with code pairing if required.
   Choose the actions in the wizard and review the displayed device and plan.
   Enter `YES` to apply it. An empty answer cancels installation.

The only Android tools you need are
[Platform-Tools with ADB](https://developer.android.com/tools/releases/platform-tools).
The assistant checks whether they are installed and helps obtain the official
version if needed. Java, Android Studio, and SDK Build-Tools are not required.
Run the wizard from the extracted kit folder:

```powershell
.\install.cmd
```

Enter the answers yourself or give them to the assistant one at a time.
Confirmation must follow your review of the current plan; it must not be
supplied in advance. If the assistant's terminal cannot run an interactive
wizard, it should prepare the command for your own terminal.

## Ready-to-copy prompt

```text
Help me install Eve Box on my Android TV using docs/one-command-setup-ru.md.
Verify the kit's source, release SHA256SUMS, manifest.json files, and ADB.
Help me connect my chosen TV; I will authorize debugging and code pairing.
Start .\install.cmd interactively, or give me the command for my own terminal.
Pass only my answers; show the exact plan before confirmation. Never supply YES in advance.
Default to installation only; HOME and app disabling require my explicit choice
and matching firmware. Do not edit profiles or bypass checks.
On errors, inspect the saved journal and follow the guide; never retry an
unknown installation outcome. Keep journals local and do not upload them.
Report the result, anything unverified, the journal path, and how to use Restore.
```

## What changes

By default, the wizard only installs and opens the app. This release is
**Eve Box 0.3.0-candidate.15 / code 36**. Its package, `io.github.ekkzy.softlumi`,
and signing key are retained for compatible updates that preserve app data.

Changing the HOME app and disabling apps are separate choices. They are
available only when the model, Android API, and firmware exactly match the
[Mi Box S 3 profile](../scripts/setup-profiles/mi-box-s3-android14.json).
Other firmware supports APK installation only. Disabling an app retains its
data. Remote-control services, screensaver, and sleep timers are not configured.

The wizard accepts only this release's pinned APK. Its signature was checked
when preparing the kit; your computer checks its size and SHA-256. The
assistant should verify the source and kit against
[SHA256SUMS.txt](https://github.com/ekkzy/eve-box/releases/download/v0.3.0-candidate.15-setup.1/SHA256SUMS.txt)
and `manifest.json`. A matching hash does not establish trust in an unknown source.

## Check and restore

After installation, check Home, Back, app launches, and favorites. A complete
wizard run on a factory-configured physical Mi Box S 3 has not yet been confirmed.

Keep the kit and its `setup-private` folder, which contains the plan and journal.
`Inspect` checks the state. After your confirmation, `Restore` returns the
recorded HOME and package changes; the installed APK and app data remain.
Restoration requires the same target, firmware, and ADB, a journal no older
than 14 days, and no conflicting changes.

If the connection is lost, use `Inspect` with the saved journal first. An unknown
installation outcome blocks automatic retries and `Restore` until the Android
installation session has been resolved. Do not delete the app, its data, or the
journal to bypass an error. See the
[check and restore commands](one-command-setup-ru.md#проверка-и-возврат).

Journals include device identity and settings. Keep them local; do not publish
them or attach them to a cloud chat. The assistant does not need your passwords
or tokens.
