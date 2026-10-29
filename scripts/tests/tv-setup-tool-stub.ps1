# Controlled tool double. It never delegates to any native Android executable.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($env:EVEBOX_TV_SETUP_TEST_MARKER -cne 'isolated-offline-fixture-v1' -or
    !$env:EVEBOX_TV_SETUP_TEST_STATE -or !$env:EVEBOX_TV_SETUP_TEST_ROLE) {
    throw 'An explicit isolated offline fixture and tool role are required.'
}
$statePath = $env:EVEBOX_TV_SETUP_TEST_STATE
$fixture = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
$role = $env:EVEBOX_TV_SETUP_TEST_ROLE
$argv = @($args | ForEach-Object { [string]$_ })
Add-Content -LiteralPath ($statePath + '.calls') -Value (@{Tool=$role; Args=$argv} | ConvertTo-Json -Compress)
function Save-Fixture { [IO.File]::WriteAllText($statePath, ($fixture | ConvertTo-Json -Depth 16)) }
function Get-Package([string]$Name) {
    $property = $fixture.Packages.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}
if ($role -eq 'apksigner') {
    if ($fixture.SignerExit -ne 0) { Write-Output 'DOES NOT VERIFY'; exit $fixture.SignerExit }
    Write-Output 'Verifies'
    Write-Output ('Signer #1 certificate SHA-256 digest: ' + $fixture.Cert)
    if ($fixture.ExtraSigner) { Write-Output ('Signer #2 certificate SHA-256 digest: ' + $fixture.Cert) }
    exit 0
}
if ($role -eq 'aapt') {
    if ($fixture.AaptExit -ne 0) { Write-Output 'Bad APK'; exit $fixture.AaptExit }
    Write-Output ("package: name='" + $fixture.CandidatePackage + "' versionCode='" + $fixture.CandidateVersion + "' versionName='controlled'")
    Write-Output "sdkVersion:'26'"
    # A real TV-only manifest is reported by aapt with this line alone.
    Write-Output ("leanback-launchable-activity: name='" + $fixture.LaunchableActivity + "' label='evebox' icon=''")
    exit 0
}
if ($role -ne 'adb') { throw 'Unknown test tool role.' }
if ($argv.Count -lt 5 -or $argv[0] -cne '-P' -or $argv[1] -cne '5037' -or $argv[2] -cne '-s') {
    throw 'The fixture rejects any unaddressed or differently addressed ADB invocation.'
}
if ($argv[3] -cne $fixture.Serial) { Write-Output 'error: selected controlled target not found'; exit 2 }
$command = ($argv | Select-Object -Skip 4) -join ' '
if ($command -match '(?:^| )(?:connect|pair|tcpip|uninstall|clear|reboot|root|remount|setprop)(?: |$)' -or
    $command -match '^shell (?:sh|su) ') { throw ('Forbidden command in offline test: ' + $command) }
$fault = $null
foreach ($candidate in @($fixture.Faults)) {
    if ($candidate.Remaining -gt 0 -and $command -match $candidate.Match) {
        $fault = $candidate; $candidate.Remaining--; Save-Fixture; break
    }
}
if ($null -ne $fault -and $fault.Mode -eq 'BeforeExit') { Write-Output 'error: controlled transport failure'; exit $fault.ExitCode }
if ($null -ne $fault -and $fault.Mode -eq 'TimeoutBefore') { Start-Sleep -Seconds $fault.Seconds }
$output = ''
switch -Regex ($command) {
    '^get-state$' { $output = $fixture.Ready; break }
    '^shell getprop ro.kernel.qemu$' { $output = $fixture.Qemu; break }
    '^shell getprop ro.product.model$' { $output = $fixture.Model; break }
    '^shell getprop ro.build.fingerprint$' { $output = $fixture.Fingerprint; break }
    '^shell getprop ro.build.version.sdk$' { $output = $fixture.Sdk; break }
    '^shell getprop ro.product.cpu.abi$' { $output = $fixture.Abi; break }
    '^shell am get-current-user$' { $output = $fixture.UserId; break }
    '^shell settings get (secure|system) ([a-z_]+)$' {
        $key = $Matches[1] + '/' + $Matches[2]
        $property = $fixture.Settings.PSObject.Properties[$key]
        if ($null -eq $property) { throw ('Unknown protected setting: ' + $key) }
        $output = $property.Value; break
    }
    '^shell pm list packages --user 0 ([A-Za-z0-9_.]+)$' {
        $name = $Matches[1]; $package = Get-Package $name
        if ($null -ne $package -and $package.Installed) { $output = 'package:' + $name }; break
    }
    '^shell dumpsys package ([A-Za-z0-9_.]+)$' {
        $name = $Matches[1]; $package = Get-Package $name
        if ($null -eq $package -or !$package.Installed) { $output = 'Unable to find package: ' + $name; break }
        $output = '  versionCode=' + $package.VersionCode + " minSdk=26 targetSdk=35`n  User 0: installed=true hidden=false suspended=false stopped=false enabled=" + $package.State
        if ($name -ceq 'io.github.ekkzy.evebox') {
            if ($package.ComponentState -eq 1) { $output += "`n    enabledComponents:`n      io.github.ekkzy.evebox.MainActivity" }
            if ($package.ComponentState -eq 2) { $output += "`n    disabledComponents:`n      io.github.ekkzy.evebox.MainActivity" }
        }
        $output += "`n  User 10: installed=true enabled=0"; break
    }
    '^shell pm path --user 0 io.github.ekkzy.evebox$' {
        if ((Get-Package 'io.github.ekkzy.evebox').Installed) { $output = 'package:/data/app/~~fixture==/evebox/base.apk' }; break
    }
    '^shell sha256sum /data/app/~~fixture==/evebox/base.apk$' {
        $output = (Get-Package 'io.github.ekkzy.evebox').Hash + '  /data/app/~~fixture==/evebox/base.apk'; break
    }
    '^shell cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME$' {
        $homePackage = Get-Package ($fixture.Home.Split('/')[0])
        if ($null -eq $homePackage -or !$homePackage.Installed -or $homePackage.State -ge 2) { $output = 'android/com.android.internal.app.ResolverActivity' }
        else { $output = $fixture.Home }; break
    }
    '^install -r --user 0 ' {
        $package = Get-Package 'io.github.ekkzy.evebox'
        $package.Installed = $true; $package.VersionCode = $fixture.CandidateVersion
        $package.Hash = if ($fixture.PostInstallHashMismatch) { 'f' * 64 } else { $fixture.CandidateHash }
        Save-Fixture; $output = 'Success'; break
    }
    '^shell am start -W -n io.github.ekkzy.evebox/io.github.ekkzy.evebox.MainActivity$' {
        $fixture.Foreground = 'io.github.ekkzy.evebox/io.github.ekkzy.evebox.MainActivity'
        Save-Fixture; $output = "Status: ok`nActivity: io.github.ekkzy.evebox/.MainActivity`nComplete"; break
    }
    '^shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME$' {
        $fixture.Foreground = $fixture.Home; Save-Fixture; $output = "Status: ok`nComplete"; break
    }
    '^shell dumpsys -t 8 window$' {
        $focus = if ($fixture.WrongForeground) { 'com.example.unrelated/com.example.unrelated.Main' } else { $fixture.Foreground }
        $output = '  mCurrentFocus=Window{controlled u0 ' + $focus + '}'; break
    }
    '^shell pm (default-state|enable|disable|disable-user|disable-until-used) --user 0 ([A-Za-z0-9_.]+)$' {
        $verb = $Matches[1]; $name = $Matches[2]; $package = Get-Package $name
        if ($null -eq $package) { throw 'Mutation of unknown fixture package.' }
        $package.State = @{ 'default-state'=0; 'enable'=1; 'disable'=2; 'disable-user'=3; 'disable-until-used'=4 }[$verb]
        if ($verb -ceq 'disable-user' -and $name -ceq 'com.google.android.apps.tv.launcherx' -and $fixture.AutoHomeAfterStockDisable) {
            $fixture.Home = $fixture.AutoHomeAfterStockDisable
        }
        Save-Fixture
        $output = 'Package ' + $name + ' new state: ' + @('default','enabled','disabled','disabled-user','disabled-until-used')[$package.State]; break
    }
    '^shell cmd package set-home-activity --user 0 ([A-Za-z0-9_./]+)$' {
        $name = $Matches[1]; $package = Get-Package ($name.Split('/')[0])
        if ($null -eq $package -or !$package.Installed -or $package.State -ge 2) { throw 'HOME cannot be assigned to a disabled/unavailable fixture package.' }
        $fixture.Home = $name; Save-Fixture; $output = 'Success'; break
    }
    default { throw ('No controlled response for ADB argv: ' + $command) }
}
if ($null -ne $fault -and $fault.Mode -eq 'AfterCommitExit') { Write-Output 'error: connection closed after controlled commit'; exit $fault.ExitCode }
if ($null -ne $fault -and $fault.Mode -eq 'TimeoutAfter') { Start-Sleep -Seconds $fault.Seconds }
Write-Output $output
exit 0
