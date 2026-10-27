# Controlled test double. It never delegates to a native Android tool.
$ErrorActionPreference = 'Stop'
if (!$env:SOFTLUMI_TEST_STATE -or !$env:SOFTLUMI_TEST_TOOL) { throw 'Test stub requires an explicit fixture state and tool role.' }
$statePath = $env:SOFTLUMI_TEST_STATE
$fixture = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
$role = $env:SOFTLUMI_TEST_TOOL
$arguments = @($args | ForEach-Object { [string]$_ })
Add-Content -LiteralPath ($statePath + '.calls') -Value (@{ Tool = $role; Args = $arguments } | ConvertTo-Json -Compress)
if ($role -eq 'apksigner') {
    if ($fixture.SignerExit -ne 0) { Write-Output 'DOES NOT VERIFY'; exit $fixture.SignerExit }
    Write-Output 'Verifies'
    Write-Output ('Signer #1 certificate SHA-256 digest: ' + $fixture.Cert)
    if ($fixture.ExtraSigner) { Write-Output ('Signer #2 certificate SHA-256 digest: ' + $fixture.Cert) }
    exit 0
}
if ($role -eq 'aapt') {
    if ($fixture.AaptExit -ne 0) { Write-Output 'Bad APK'; exit $fixture.AaptExit }
    Write-Output ("package: name='" + $fixture.Package + "' versionCode='" + $fixture.CandidateVersion + "' versionName='test'")
    Write-Output ("leanback-launchable-activity: name='" + $fixture.Package + ".MainActivity' label='softlumi' icon=''")
    exit 0
}
if ($role -ne 'adb') { throw 'Unknown controlled tool role.' }
if ($arguments.Count -lt 3 -or $arguments[0] -cne '-s') { throw 'Test rejected unaddressed ADB command.' }
if ($arguments[1] -cne $fixture.Serial) { Write-Output 'error: selected target not found'; exit 1 }
$command = ($arguments | Select-Object -Skip 2) -join ' '
if ($command -match '(?:^| )(?:connect|pair|tcpip|uninstall|clear|reboot)(?: |$)') { throw "Forbidden ADB command in test: $command" }
switch -Regex ($command) {
    '^get-state$' { if ($fixture.TargetExit -ne 0) { Write-Output 'device offline'; exit $fixture.TargetExit }; Write-Output $fixture.Ready; exit 0 }
    '^shell getprop ro.kernel.qemu$' { Write-Output $fixture.Qemu; exit 0 }
    '^shell getprop ro.product.model$' { Write-Output $fixture.Model; exit 0 }
    '^shell getprop ro.build.fingerprint$' { Write-Output $fixture.Fingerprint; exit 0 }
    '^shell am get-current-user$' { Write-Output $fixture.User; exit 0 }
    '^shell pm list packages --user 0 io.github.ekkzy.softlumi$' {
        if ($fixture.Installed) { Write-Output 'package:io.github.ekkzy.softlumi' }; exit 0
    }
    '^shell dumpsys package io.github.ekkzy.softlumi$' {
        Write-Output ('  versionCode=' + $fixture.InstalledVersion + ' minSdk=26 targetSdk=35')
        Write-Output '  User 0: ceDataInode=123 installed=true hidden=false suspended=false stopped=false enabled=0'
        if ($fixture.ComponentState -eq 1) { Write-Output "    enabledComponents:`n      io.github.ekkzy.softlumi.MainActivity" }
        if ($fixture.ComponentState -eq 2) { Write-Output "    disabledComponents:`n      io.github.ekkzy.softlumi.MainActivity" }
        Write-Output '  User 10: installed=true enabled=0'
        Write-Output "    disabledComponents:`n      io.github.ekkzy.softlumi.OtherActivity"
        exit 0
    }
    '^shell cmd package resolve-activity ' { Write-Output $fixture.Home; exit 0 }
    '^install -r --user 0 ' {
        # Even a native exit of zero can carry an install failure; test both forms.
        if ($fixture.InstallFailure) { Write-Output 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'; exit $fixture.InstallExit }
        $fixture.Installed = $true
        $fixture.InstalledVersion = $fixture.CandidateVersion
        if ($fixture.PostInstallMismatch) { $fixture.InstalledVersion = '999' }
        if ($fixture.PostInstallComponentState -ge 0) { $fixture.ComponentState = $fixture.PostInstallComponentState }
        [IO.File]::WriteAllText($statePath, ($fixture | ConvertTo-Json))
        Write-Output 'Success'; exit 0
    }
    '^shell pm (enable|disable|default-state) --user 0 io.github.ekkzy.softlumi/io.github.ekkzy.softlumi.MainActivity$' {
        $verb = $Matches[1]
        if ($fixture.ComponentMutationExit -ne 0) { Write-Output 'SecurityException: Shell cannot change component state'; exit $fixture.ComponentMutationExit }
        $fixture.ComponentState = switch ($verb) { 'enable' { 1 }; 'disable' { 2 }; 'default-state' { 0 } }
        [IO.File]::WriteAllText($statePath, ($fixture | ConvertTo-Json))
        $stateName = @('default', 'enabled', 'disabled')[$fixture.ComponentState]
        Write-Output "Component io.github.ekkzy.softlumi/.MainActivity new state: $stateName"; exit 0
    }
    '^shell cmd package set-home-activity --user 0 ([A-Za-z0-9_./]+)$' {
        if ($fixture.HomeFailure) { Write-Output 'Error: failed to set HOME'; exit 1 }
        $fixture.Home = $Matches[1]
        [IO.File]::WriteAllText($statePath, ($fixture | ConvertTo-Json))
        Write-Output 'Success'; exit 0
    }
    default { throw "Controlled stub has no response for ADB command: $command" }
}
