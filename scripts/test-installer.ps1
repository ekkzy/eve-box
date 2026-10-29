#requires -Version 5.1
<# .SYNOPSIS
Runs installer/rollback cases entirely against local controlled tool doubles.
No real adb, apksigner, aapt, SDK, APK, emulator or device is used.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Keep journal rename operations in the writable checkout. Some Windows sandboxes
# allow TEMP file creation but deny rename there, which is unrelated to ADB.
$testRoot = Join-Path $PSScriptRoot ('.installer-test-' + [guid]::NewGuid().ToString('N'))
[void](New-Item -ItemType Directory -Path $testRoot)
$hostExe = (Get-Process -Id $PID).Path
$stubSource = Join-Path $PSScriptRoot 'tests\tool-stub.ps1'
$stubContents = Get-Content -LiteralPath $stubSource -Raw
$oldState = $env:EVEBOX_TEST_STATE
$oldTool = $env:EVEBOX_TEST_TOOL
$passes = 0
$fails = 0
$results = @()
foreach ($role in @('adb', 'aapt', 'apksigner')) {
    $header = '$env:EVEBOX_TEST_TOOL = ''' + $role + "'`n"
    [IO.File]::WriteAllText((Join-Path $testRoot ($role + '.ps1')), $header + $stubContents)
}
$apk = Join-Path $testRoot 'fixture with spaces.apk'
[IO.File]::WriteAllText($apk, 'controlled test bytes, not an APK')
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
$cert = 'a' * 64
$statePath = Join-Path $testRoot 'fixture.json'
$env:EVEBOX_TEST_STATE = $statePath

function New-Fixture {
    $value = [ordered]@{
        Serial = 'emulator-5554'; Ready = 'device'; Qemu = '1'; Model = 'Controlled TV emulator'; Fingerprint = 'stub/test/device:35/fake/build:userdebug/test-keys'; User = '0'
        Package = 'io.github.ekkzy.evebox'; Cert = $cert; CandidateVersion = '2'; Installed = $false; InstalledVersion = '1'; ComponentState = 0
        Home = 'com.example.stock/.HomeActivity'; InstallFailure = $false; InstallExit = 1; TargetExit = 0; SignerExit = 0; AaptExit = 0
        HomeFailure = $false; PostInstallMismatch = $false; ExtraSigner = $false; ComponentMutationExit = 0; PostInstallComponentState = -1
        TargetInventory = @('emulator-5554', 'emulator-5556', 'physical-device')
    }
    [IO.File]::WriteAllText($statePath, ($value | ConvertTo-Json))
    if (Test-Path -LiteralPath ($statePath + '.calls')) { Remove-Item -LiteralPath ($statePath + '.calls') -Force }
}
function Set-Fixture {
    param([hashtable]$Changes)
    $value = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    foreach ($key in $Changes.Keys) { $value.$key = $Changes[$key] }
    [IO.File]::WriteAllText($statePath, ($value | ConvertTo-Json))
}
function Get-Calls {
    if (Test-Path -LiteralPath ($statePath + '.calls')) { Get-Content -LiteralPath ($statePath + '.calls') | ForEach-Object { $_ | ConvertFrom-Json } }
}
function Get-ComponentMutations {
    Get-Calls | Where-Object { $_.Tool -eq 'adb' -and ($_.Args -join ' ') -match 'shell pm (enable|disable|default-state) --user 0 ' }
}
function Assert-That {
    param([bool]$Condition, [string]$Message)
    if (!$Condition) { throw $Message }
}
function Invoke-CaseInstaller {
    param([string[]]$Extra = @(), [switch]$NoSerial, [switch]$DefaultJournal, [string]$HashOverride = $hash, [string]$CertOverride = $cert)
    $commandArgs = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'install.ps1'), '-Apk', $apk,
        '-ApkSha256', $HashOverride, '-SigningCertSha256', $CertOverride,
        '-AdbPath', (Join-Path $testRoot 'adb.ps1'), '-ApkSignerPath', (Join-Path $testRoot 'apksigner.ps1'), '-AaptPath', (Join-Path $testRoot 'aapt.ps1'))
    if (!$NoSerial) { $commandArgs += @('-EmulatorSerial', 'emulator-5554') }
    if (!$DefaultJournal) { $commandArgs += @('-JournalPath', (Join-Path $testRoot 'journal.json')) }
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $hostExe @commandArgs @Extra 2>&1) -join "`n"
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    return [pscustomobject]@{ Code = $code; Output = $output }
}
function Invoke-CaseRollback {
    param([string[]]$Extra = @())
    $commandArgs = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'rollback.ps1'), '-JournalPath', (Join-Path $testRoot 'journal.json'),
        '-EmulatorSerial', 'emulator-5554', '-AdbPath', (Join-Path $testRoot 'adb.ps1'))
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $hostExe @commandArgs @Extra 2>&1) -join "`n"
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    return [pscustomobject]@{ Code = $code; Output = $output }
}
function Test-Case {
    param([string]$Name, [scriptblock]$Body)
    New-Fixture
    $journalPath = Join-Path $testRoot 'journal.json'
    if (Test-Path -LiteralPath $journalPath) { Remove-Item -LiteralPath $journalPath -Force }
    try {
        & $Body
        $script:passes++
        $script:results += [pscustomobject]@{ Name = $Name; Passed = $true }
        Write-Output "PASS $Name"
    } catch {
        $script:fails++
        $script:results += [pscustomobject]@{ Name = $Name; Passed = $false; Error = $_.Exception.Message }
        Write-Output "FAIL $Name : $($_.Exception.Message)"
    }
}
try {
    Test-Case 'dry-run validates locally with zero ADB calls and no journal' {
        $r = Invoke-CaseInstaller -Extra @('-DryRun', '-SetHome')
        Assert-That ($r.Code -eq 0) $r.Output
        Assert-That (@(Get-Calls | Where-Object Tool -eq 'adb').Count -eq 0) 'Dry-run invoked ADB'
        Assert-That (!(Test-Path -LiteralPath $journalPath)) 'Dry-run wrote a journal'
        Assert-That ($r.Output -match 'Would run: adb -s emulator-5554 install -r') 'Dry-run omitted installation plan'
    }
    Test-Case 'multiple possible targets with no explicit serial selects none' {
        # The environment intentionally advertises a physical serial; explicit arguments must win.
        $saved = $env:ANDROID_SERIAL
        try { $env:ANDROID_SERIAL = 'physical-device'; $r = Invoke-CaseInstaller -NoSerial } finally { $env:ANDROID_SERIAL = $saved }
        Assert-That ($r.Code -ne 0) 'Missing serial succeeded'
        Assert-That (@(Get-Calls).Count -eq 0) 'Missing serial launched tools'
    }
    Test-Case 'APK hash mismatch stops before ADB' {
        $r = Invoke-CaseInstaller -HashOverride ('b' * 64)
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'SHA-256 mismatch') $r.Output
        Assert-That (@(Get-Calls | Where-Object Tool -eq 'adb').Count -eq 0) 'Hash mismatch invoked ADB'
    }
    Test-Case 'certificate mismatch stops before ADB' {
        $r = Invoke-CaseInstaller -CertOverride ('b' * 64)
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'certificate SHA-256 mismatch') $r.Output
        Assert-That (@(Get-Calls | Where-Object Tool -eq 'adb').Count -eq 0) 'Certificate mismatch invoked ADB'
    }
    Test-Case 'package mismatch stops before ADB' {
        Set-Fixture @{ Package = 'com.example.wrong' }
        $r = Invoke-CaseInstaller
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'package mismatch') $r.Output
        Assert-That (@(Get-Calls | Where-Object Tool -eq 'adb').Count -eq 0) 'Package mismatch invoked ADB'
    }
    Test-Case 'apksigner nonzero exit stops before ADB' {
        Set-Fixture @{ SignerExit = 7 }
        $r = Invoke-CaseInstaller
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'exit 7') $r.Output
        Assert-That (@(Get-Calls | Where-Object Tool -eq 'adb').Count -eq 0) 'Bad signature invoked ADB'
    }
    Test-Case 'multiple APK signers rejected before ADB' {
        Set-Fixture @{ ExtraSigner = $true }
        $r = Invoke-CaseInstaller
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'Exactly one') $r.Output
        Assert-That (@(Get-Calls | Where-Object Tool -eq 'adb').Count -eq 0) 'Multiple signers invoked ADB'
    }
    Test-Case 'aapt nonzero exit stops before ADB' {
        Set-Fixture @{ AaptExit = 8 }
        $r = Invoke-CaseInstaller
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'exit 8') $r.Output
        Assert-That (@(Get-Calls | Where-Object Tool -eq 'adb').Count -eq 0) 'Bad metadata invoked ADB'
    }
    Test-Case 'physical-looking serial rejected without Stage B' {
        $r = Invoke-CaseInstaller -NoSerial -Extra @('-DeviceSerial', 'physical-device')
        Assert-That ($r.Code -ne 0) 'Physical serial succeeded in Stage A'
        Assert-That (@(Get-Calls).Count -eq 0) 'Physical serial launched tools'
    }
    Test-Case 'emulator serial with non-emulator qemu is rejected' {
        Set-Fixture @{ Qemu = '0' }
        $r = Invoke-CaseInstaller
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'ro.kernel.qemu=1') $r.Output
        Assert-That (@(Get-Calls | Where-Object { $_.Tool -eq 'adb' -and $_.Args[2] -eq 'install' }).Count -eq 0) 'Target mismatch installed APK'
    }
    Test-Case 'unready selected target rejects before mutation' {
        Set-Fixture @{ TargetExit = 3 }
        $r = Invoke-CaseInstaller
        Assert-That ($r.Code -ne 0) 'Offline target succeeded'
        Assert-That (!(Test-Path -LiteralPath $journalPath)) 'Offline target wrote mutation journal'
    }
    Test-Case 'unauthorized state with zero native exit rejects before mutation' {
        Set-Fixture @{ Ready = 'unauthorized' }
        $r = Invoke-CaseInstaller
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'not ready') $r.Output
        Assert-That (!(Test-Path -LiteralPath $journalPath)) 'Unauthorized target wrote journal'
    }
    Test-Case 'Stage B identity mismatch rejects before mutation' {
        Set-Fixture @{ Serial = 'physical-fixture'; Qemu = '0' }
        $r = Invoke-CaseInstaller -NoSerial -Extra @('-StageBPhysicalDevice', '-DeviceSerial', 'physical-fixture', '-ExpectedModel', 'wrong model', '-ExpectedBuildFingerprint', 'wrong fingerprint')
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'does not match') $r.Output
        Assert-That (!(Test-Path -LiteralPath $journalPath)) 'Identity mismatch wrote journal'
    }
    Test-Case 'install nonzero failure persists failure journal and preserves HOME' {
        Set-Fixture @{ InstallFailure = $true; InstallExit = 5 }
        $r = Invoke-CaseInstaller -Extra @('-SetHome')
        Assert-That ($r.Code -ne 0) 'Install failure succeeded'
        $j = Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
        Assert-That ($j.Status -eq 'InstallFailed') 'Missing failure journal'
        Assert-That (@(Get-Calls | Where-Object { ($_.Args -join ' ') -match 'set-home-activity|pm enable' }).Count -eq 0) 'Install failure mutated HOME'
    }
    Test-Case 'install textual failure with zero exit is rejected' {
        Set-Fixture @{ InstallFailure = $true; InstallExit = 0 }
        $r = Invoke-CaseInstaller
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'did not report install success') $r.Output
    }
    Test-Case 'post-install version mismatch is a failure' {
        Set-Fixture @{ PostInstallMismatch = $true }
        $r = Invoke-CaseInstaller
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'did not match') $r.Output
    }
    Test-Case 'update uses install -r and leaves HOME unchanged' {
        Set-Fixture @{ Installed = $true; InstalledVersion = '1'; ComponentState = 2 }
        $r = Invoke-CaseInstaller
        Assert-That ($r.Code -eq 0) $r.Output
        $j = Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
        Assert-That ($j.Previous.Installed -and $j.Previous.ComponentState -eq 2 -and $j.Status -eq 'Installed') 'Wrong captured update state'
        $calls = @(Get-Calls | Where-Object Tool -eq 'adb')
        Assert-That (@($calls | Where-Object { ($_.Args -join ' ') -match '^-s emulator-5554 install -r --user 0 ' }).Count -eq 1) 'Missing addressed install -r'
        Assert-That (@($calls | Where-Object { ($_.Args -join ' ') -match 'set-home-activity|pm enable|uninstall| clear ' }).Count -eq 0) 'Update changed HOME or data'
    }
    Test-Case 'newer installed version refuses downgrade' {
        Set-Fixture @{ Installed = $true; InstalledVersion = '3' }
        $r = Invoke-CaseInstaller
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'Downgrades are refused') $r.Output
    }
    Test-Case 'existing journal refuses new installation before ADB' {
        $r = Invoke-CaseInstaller; Assert-That ($r.Code -eq 0) $r.Output
        $before = Get-Content -LiteralPath $journalPath -Raw
        Remove-Item -LiteralPath ($statePath + '.calls') -Force
        $r = Invoke-CaseInstaller
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'already exists') $r.Output
        Assert-That (@(Get-Calls).Count -eq 0) 'Existing journal invoked tools'
        Assert-That ((Get-Content -LiteralPath $journalPath -Raw) -ceq $before) 'Existing journal overwritten'
    }
    foreach ($state in @(0, 1, 2)) {
        $capturedState = $state
        Test-Case "rollback restores exact component override $state and captured HOME" {
            Set-Fixture @{ Installed = $true; InstalledVersion = '1'; ComponentState = $capturedState }
            $r = Invoke-CaseInstaller -Extra @('-SetHome')
            Assert-That ($r.Code -eq 0) $r.Output
            $expectedMutations = if ($capturedState -eq 2) { 1 } else { 0 }
            Assert-That (@(Get-ComponentMutations).Count -eq $expectedMutations) 'Install changed an already-enabled override'
            Remove-Item -LiteralPath ($statePath + '.calls') -Force
            $r = Invoke-CaseRollback
            Assert-That ($r.Code -eq 0) $r.Output
            Assert-That (@(Get-ComponentMutations).Count -eq $expectedMutations) 'Rollback issued an unnecessary state command or omitted a needed restore'
            $f = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
            Assert-That ($f.Home -eq 'com.example.stock/.HomeActivity' -and $f.ComponentState -eq $capturedState -and $f.Installed) 'Rollback lost exact state or removed app'
            $r = Invoke-CaseRollback
            Assert-That ($r.Code -ne 0) 'Already consumed journal accepted'
        }
    }
    foreach ($state in @(0, 1)) {
        $capturedState = $state
        Test-Case "default or enabled override $state survives HOME install and rollback when shell state changes are forbidden" {
            Set-Fixture @{ Installed = $true; ComponentState = $capturedState; ComponentMutationExit = 7 }
            $r = Invoke-CaseInstaller -Extra @('-SetHome')
            Assert-That ($r.Code -eq 0) $r.Output
            $j = Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
            Assert-That ($j.Previous.ComponentState -eq $capturedState -and $j.Status -eq 'HomeChanged') 'Already-enabled override was not preserved in the journal'
            $r = Invoke-CaseRollback
            Assert-That ($r.Code -eq 0) $r.Output
            Assert-That (@(Get-ComponentMutations).Count -eq 0) 'An unnecessary component state command was attempted'
            $f = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
            Assert-That ($f.ComponentState -eq $capturedState -and $f.Home -eq 'com.example.stock/.HomeActivity') 'No-op state path failed to preserve the captured override'
        }
    }
    Test-Case 'disabled component enable denial is a meaningful failure and rollback needs no redundant state command' {
        Set-Fixture @{ Installed = $true; ComponentState = 2; ComponentMutationExit = 7 }
        $r = Invoke-CaseInstaller -Extra @('-SetHome')
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'SecurityException') $r.Output
        $j = Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
        Assert-That ($j.Status -eq 'HomeChangeFailed' -and $j.Previous.ComponentState -eq 2) 'Enable denial lost its recoverable state'
        Assert-That (@(Get-ComponentMutations).Count -eq 1) 'Disabled component did not attempt the required enable'
        Assert-That (@(Get-Calls | Where-Object { ($_.Args -join ' ') -match 'set-home-activity' }).Count -eq 0) 'Enable denial continued to HOME selection'
        Remove-Item -LiteralPath ($statePath + '.calls') -Force
        $r = Invoke-CaseRollback
        Assert-That ($r.Code -eq 0) $r.Output
        Assert-That (@(Get-ComponentMutations).Count -eq 0) 'Rollback changed an override already equal to the journal'
        $f = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        Assert-That ($f.ComponentState -eq 2 -and $f.Home -eq 'com.example.stock/.HomeActivity') 'Denied enable changed the captured component/HOME'
    }
    Test-Case 'HOME enable decision uses current post-install override and rollback restores the original default' {
        Set-Fixture @{ Installed = $true; ComponentState = 0; PostInstallComponentState = 2 }
        $r = Invoke-CaseInstaller -Extra @('-SetHome')
        Assert-That ($r.Code -eq 0) $r.Output
        Assert-That (@(Get-ComponentMutations).Count -eq 1) 'Post-install disabled component was not enabled'
        $r = Invoke-CaseRollback
        Assert-That ($r.Code -eq 0) $r.Output
        $f = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        Assert-That ($f.ComponentState -eq 0) 'Rollback used the post-install state instead of the original captured override'
    }
    foreach ($state in @(0, 1)) {
        $capturedState = $state
        Test-Case "rollback restores captured override $state when the actual component differs" {
            Set-Fixture @{ Installed = $true; ComponentState = $capturedState }
            $r = Invoke-CaseInstaller -Extra @('-SetHome'); Assert-That ($r.Code -eq 0) $r.Output
            Set-Fixture @{ ComponentState = 2 }
            Remove-Item -LiteralPath ($statePath + '.calls') -Force
            $r = Invoke-CaseRollback
            Assert-That ($r.Code -eq 0) $r.Output
            Assert-That (@(Get-ComponentMutations).Count -eq 1) 'Rollback did not perform exactly one necessary state restore'
            $f = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
            Assert-That ($f.ComponentState -eq $capturedState) 'Rollback failed to restore the exact captured override'
        }
    }
    Test-Case 'needed rollback component change denial remains nonzero with failure journal' {
        $r = Invoke-CaseInstaller -Extra @('-SetHome'); Assert-That ($r.Code -eq 0) $r.Output
        Set-Fixture @{ ComponentState = 2; ComponentMutationExit = 7 }
        Remove-Item -LiteralPath ($statePath + '.calls') -Force
        $r = Invoke-CaseRollback
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'SecurityException') $r.Output
        $j = Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
        Assert-That ($j.Status -eq 'RollbackFailed') 'Needed restore denial was incorrectly reported as complete'
        Assert-That (@(Get-ComponentMutations).Count -eq 1) 'Needed restore was silently skipped'
        $f = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        Assert-That ($f.ComponentState -eq 2) 'Denied restore unexpectedly changed state'
    }
    Test-Case 'rollback dry-run runs zero ADB and leaves journal untouched' {
        $r = Invoke-CaseInstaller -Extra @('-SetHome'); Assert-That ($r.Code -eq 0) $r.Output
        $before = Get-Content -LiteralPath $journalPath -Raw
        Remove-Item -LiteralPath ($statePath + '.calls') -Force
        $r = Invoke-CaseRollback -Extra @('-DryRun')
        Assert-That ($r.Code -eq 0) $r.Output
        Assert-That (@(Get-Calls).Count -eq 0) 'Rollback dry-run called ADB'
        Assert-That ((Get-Content -LiteralPath $journalPath -Raw) -ceq $before) 'Rollback dry-run changed journal'
    }
    Test-Case 'legacy journal refused unchanged before ADB' {
        $r = Invoke-CaseInstaller; Assert-That ($r.Code -eq 0) $r.Output
        $j = Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
        $j.Tool = 'softlumi-installer'; $j.Package = 'io.github.ekkzy.softlumi'
        $j.Component = 'io.github.ekkzy.softlumi/io.github.ekkzy.softlumi.MainActivity'
        [IO.File]::WriteAllText($journalPath, ($j | ConvertTo-Json -Depth 8))
        $before = [IO.File]::ReadAllText($journalPath)
        Remove-Item -LiteralPath ($statePath + '.calls') -Force
        $r = Invoke-CaseRollback
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'original setup kit') $r.Output
        Assert-That (@(Get-Calls).Count -eq 0 -and [IO.File]::ReadAllText($journalPath) -ceq $before) 'Legacy rejection queried ADB or rewrote the journal'
    }
    Test-Case 'legacy default journal is retained and not silently bypassed' {
        $legacyPath = Join-Path $testRoot 'softlumi-install-journal.json'
        [IO.File]::WriteAllText($legacyPath, '{"Tool":"softlumi-installer"}')
        $before = [IO.File]::ReadAllText($legacyPath)
        Push-Location $testRoot
        try { $r = Invoke-CaseInstaller -DefaultJournal } finally { Pop-Location }
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'select a new -JournalPath explicitly') $r.Output
        Assert-That (@(Get-Calls).Count -eq 0 -and [IO.File]::ReadAllText($legacyPath) -ceq $before) 'Legacy default journal guard ran tools or changed saved bytes'
        Assert-That (!(Test-Path -LiteralPath (Join-Path $testRoot 'eve-box-install-journal.json'))) 'Guard created a new default journal'
        Remove-Item -LiteralPath $legacyPath -Force
    }
    Test-Case 'stale journal rejected before ADB' {
        $r = Invoke-CaseInstaller -Extra @('-SetHome'); Assert-That ($r.Code -eq 0) $r.Output
        $j = Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
        $j.CreatedUtc = [DateTime]::UtcNow.AddDays(-15).ToString('o')
        [IO.File]::WriteAllText($journalPath, ($j | ConvertTo-Json -Depth 8))
        Remove-Item -LiteralPath ($statePath + '.calls') -Force
        $r = Invoke-CaseRollback
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'stale') $r.Output
        Assert-That (@(Get-Calls).Count -eq 0) 'Stale journal called ADB'
    }
    Test-Case 'wrong journal target rejected before ADB' {
        $r = Invoke-CaseInstaller; Assert-That ($r.Code -eq 0) $r.Output
        $j = Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
        $j.Target.Serial = 'emulator-9999'
        [IO.File]::WriteAllText($journalPath, ($j | ConvertTo-Json -Depth 8))
        Remove-Item -LiteralPath ($statePath + '.calls') -Force
        $r = Invoke-CaseRollback
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'does not match') $r.Output
        Assert-That (@(Get-Calls).Count -eq 0) 'Wrong target journal called ADB'
    }
    Test-Case 'invalid component state journal rejected before ADB' {
        $r = Invoke-CaseInstaller -Extra @('-SetHome'); Assert-That ($r.Code -eq 0) $r.Output
        $j = Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
        $j.Previous.ComponentState = 3
        [IO.File]::WriteAllText($journalPath, ($j | ConvertTo-Json -Depth 8))
        Remove-Item -LiteralPath ($statePath + '.calls') -Force
        $r = Invoke-CaseRollback
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'state is invalid') $r.Output
        Assert-That (@(Get-Calls).Count -eq 0) 'Invalid state journal called ADB'
    }
    Test-Case 'changed app version refuses rollback' {
        $r = Invoke-CaseInstaller -Extra @('-SetHome'); Assert-That ($r.Code -eq 0) $r.Output
        Set-Fixture @{ InstalledVersion = '3' }
        Remove-Item -LiteralPath ($statePath + '.calls') -Force
        $r = Invoke-CaseRollback
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'version changed') $r.Output
        Assert-That (@(Get-Calls | Where-Object { ($_.Args -join ' ') -match 'set-home-activity|pm (enable|disable|default-state)' }).Count -eq 0) 'Version conflict mutated target'
    }
    Test-Case 'changed firmware rejects rollback without mutation' {
        $r = Invoke-CaseInstaller -Extra @('-SetHome'); Assert-That ($r.Code -eq 0) $r.Output
        Set-Fixture @{ Fingerprint = 'changed/firmware' }
        Remove-Item -LiteralPath ($statePath + '.calls') -Force
        $r = Invoke-CaseRollback
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'changed since') $r.Output
        Assert-That (@(Get-Calls | Where-Object { ($_.Args -join ' ') -match 'set-home-activity|pm (enable|disable|default-state)' }).Count -eq 0) 'Firmware mismatch mutated target'
    }
    Test-Case 'HOME change failure remains recoverable' {
        Set-Fixture @{ Installed = $true; ComponentState = 2; HomeFailure = $true }
        $r = Invoke-CaseInstaller -Extra @('-SetHome')
        Assert-That ($r.Code -ne 0) 'HOME failure succeeded'
        $j = Get-Content -LiteralPath $journalPath -Raw | ConvertFrom-Json
        Assert-That ($j.Status -eq 'HomeChangeFailed' -and $j.Previous.ComponentState -eq 2) 'HOME failure lost previous state'
        Set-Fixture @{ HomeFailure = $false }
        $r = Invoke-CaseRollback
        Assert-That ($r.Code -eq 0) $r.Output
    }
    Test-Case 'later HOME change rejects stale transaction action' {
        $r = Invoke-CaseInstaller -Extra @('-SetHome'); Assert-That ($r.Code -eq 0) $r.Output
        Set-Fixture @{ Home = 'com.example.other/.HomeActivity' }
        $r = Invoke-CaseRollback
        Assert-That ($r.Code -ne 0 -and $r.Output -match 'another action') $r.Output
    }
    Write-Output "Installer controlled tests: $passes passed, $fails failed. No actual Android tools or devices were used."
    if ($fails -gt 0) { exit 1 }
    exit 0
} finally {
    $env:EVEBOX_TEST_STATE = $oldState
    $env:EVEBOX_TEST_TOOL = $oldTool
    # Remove individual known fixture files; never recursively delete a computed directory.
    foreach ($file in @(Get-ChildItem -LiteralPath $testRoot -File)) { Remove-Item -LiteralPath $file.FullName -Force }
    Remove-Item -LiteralPath $testRoot -Force
}
