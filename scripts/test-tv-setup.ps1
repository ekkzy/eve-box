#requires -Version 5.1
<# .SYNOPSIS
Offline setup transaction tests. Uses only isolated PowerShell tool doubles;
no SDK, valid APK, emulator, device, network or real ADB is used.
#>
[CmdletBinding()]
param([string]$ReportPath = '', [string[]]$Case = @(), [switch]$KeepFixtures)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$hostExe = (Get-Process -Id $PID).Path
$setup = Join-Path $PSScriptRoot 'setup-tv.ps1'
$driver = Join-Path $PSScriptRoot 'tests\tv-setup-driver.ps1'
if (!(Test-Path -LiteralPath $setup -PathType Leaf)) { throw 'setup-tv.ps1 is not ready; no tool was launched.' }
$testRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ('.tv-setup-test-' + [guid]::NewGuid().ToString('N'))))
[void](New-Item -ItemType Directory -Path $testRoot)
$marker = [guid]::NewGuid().ToString('N')
$controlledSerial = 'controlled-tv-' + $marker.Substring(0,12) + ':5555'
[IO.File]::WriteAllText((Join-Path $testRoot '.owned-fixture'), $marker)
$oldState = $env:EVEBOX_TV_SETUP_TEST_STATE
$oldRole = $env:EVEBOX_TV_SETUP_TEST_ROLE
$oldMarker = $env:EVEBOX_TV_SETUP_TEST_MARKER
$env:EVEBOX_TV_SETUP_TEST_MARKER = 'isolated-offline-fixture-v1'
$results = @(); $caseNumber = 0; $backendCallDurations = @()
$cert = 'a' * 64
$soft = 'io.github.ekkzy.evebox'
$stock = 'com.google.android.apps.tv.launcherx'
$softHome = $soft + '/' + $soft + '.MainActivity'
$oldHome = $stock + '/' + $stock + '.home.HomeActivity'
$profile = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'setup-profiles\mi-box-s3-android14.json') -Raw | ConvertFrom-Json
$optional = @($profile.packages | Where-Object role -eq 'optional' | ForEach-Object packageName)
$apk = Join-Path $testRoot 'fixture & literal spaces.apk'
[IO.File]::WriteAllText($apk, 'Controlled offline fixture bytes. This is not an APK.')
$apkHash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$stub = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'tests\tv-setup-tool-stub.ps1') -Raw
$tools = @{}
foreach ($role in @('adb', 'aapt', 'apksigner')) {
    $path = Join-Path $testRoot ($role + ' controlled.ps1')
    [IO.File]::WriteAllText($path, ('$env:EVEBOX_TV_SETUP_TEST_ROLE = ''' + $role + "'`n" + $stub))
    $tools[$role] = $path
}

function Write-Json([string]$Path, $Value) { [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 24)) }
function Read-Json([string]$Path) { Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
function Assert-That([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Get-Fixture { Read-Json $script:statePath }
function Set-Fixture([hashtable]$Changes) {
    $state = Get-Fixture
    foreach ($key in $Changes.Keys) { $state.$key = $Changes[$key] }
    Write-Json $script:statePath $state
}
function Set-PackageState([string]$Package, [int]$Value) {
    $state = Get-Fixture; $state.Packages.PSObject.Properties[$Package].Value.State = $Value
    Write-Json $script:statePath $state
}
function Get-Calls {
    if (Test-Path -LiteralPath ($script:statePath + '.calls')) {
        Get-Content -LiteralPath ($script:statePath + '.calls') | ForEach-Object { $_ | ConvertFrom-Json }
    }
}
function Clear-Calls {
    if (Test-Path -LiteralPath ($script:statePath + '.calls')) { Remove-Item -LiteralPath ($script:statePath + '.calls') -Force }
}
function Get-Mutations {
    Get-Calls | Where-Object { $_.Tool -eq 'adb' -and (($_.Args | Select-Object -Skip 4) -join ' ') -match '^(install |shell (?:pm (?:enable|disable|disable-user|disable-until-used|default-state) |cmd package set-home-activity |am start ))' }
}
function Get-PackageMutations([string]$Package) {
    Get-Mutations | Where-Object { ($_.Args -join ' ') -match ('shell pm (?:enable|disable|disable-user|disable-until-used|default-state) --user 0 ' + [regex]::Escape($Package) + '$') }
}
function Base-Parameters([string]$Mode) {
    return @{ Mode=$Mode; Serial=$controlledSerial; ExpectedModel=[string]$profile.match.model;
        ExpectedFingerprint=[string]$profile.match.fingerprint; AdbPath=$tools.adb; AdbServerPort=5037;
        CommandTimeoutSeconds=10; InstallTimeoutSeconds=10 }
}
function Quote-ProcessArgument([string]$Value) {
    # Windows argv quoting, never shell evaluation. All values here are driver
    # paths; untrusted installer parameters travel only through the JSON file.
    $escaped=[regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped=[regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}
function Invoke-Setup([hashtable]$Parameters, [string]$ExecutablePath=$hostExe) {
    $path = Join-Path $script:caseDir ('arguments-' + [guid]::NewGuid().ToString('N') + '.json')
    Write-Json $path $Parameters
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $process=New-Object Diagnostics.Process
    $process.StartInfo=New-Object Diagnostics.ProcessStartInfo
    $process.StartInfo.FileName=$ExecutablePath
    $process.StartInfo.UseShellExecute=$false; $process.StartInfo.CreateNoWindow=$true
    $process.StartInfo.RedirectStandardOutput=$true; $process.StartInfo.RedirectStandardError=$true
    $commandArgs=@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',$driver,'-ScriptPath',$setup,'-ParametersPath',$path)
    $process.StartInfo.Arguments=(@($commandArgs | ForEach-Object {Quote-ProcessArgument $_}) -join ' ')
    try {
        [void]$process.Start()
        $stdout=$process.StandardOutput.ReadToEndAsync(); $stderr=$process.StandardError.ReadToEndAsync()
        if(!$process.WaitForExit(50000)){
            # Kill only this owned child and its descendants. A timeout is a test
            # failure, never accepted as the expected refusal of an unsafe input.
            $killer=New-Object Diagnostics.Process
            $killer.StartInfo=New-Object Diagnostics.ProcessStartInfo
            $killer.StartInfo.FileName=Join-Path $env:SystemRoot 'System32\taskkill.exe'
            $killer.StartInfo.Arguments='/PID '+$process.Id+' /T /F'
            $killer.StartInfo.UseShellExecute=$false; $killer.StartInfo.CreateNoWindow=$true
            [void]$killer.Start()
            if(!$killer.WaitForExit(4000)){ $killer.Kill() }
            $killer.Dispose()
            if(!$process.WaitForExit(2000)){ $process.Kill() }
            throw 'Backend child exceeded the independent50s test deadline (not an expected rejection).'
        }
        Assert-That ($stdout.Wait(1000) -and $stderr.Wait(1000)) 'Backend child left its output pipes open.'
        $output=$stdout.Result+"`n"+$stderr.Result; $exitCode=$process.ExitCode
        Assert-That ($output -notmatch 'No controlled response for ADB argv|The fixture rejects|Unknown protected setting|Mutation of unknown fixture|explicit isolated offline fixture|Unknown test tool role') 'The controlled tool harness failed; this is not an expected installer rejection.'
    } finally { $watch.Stop(); $script:backendCallDurations += $watch.Elapsed.TotalSeconds; $process.Dispose() }
    return [pscustomobject]@{Code=$exitCode; Output=$output; Seconds=$watch.Elapsed.TotalSeconds}
}
function Plan-Parameters([hashtable]$Extra=@{}) {
    $p = Base-Parameters 'Plan'
    $p.Apk=$apk; $p.ApkSha256=$apkHash; $p.SigningCertSha256=$cert
    $p.ApkSignerPath=$tools.apksigner; $p.AaptPath=$tools.aapt; $p.PlanPath=$script:planPath
    foreach ($key in $Extra.Keys) { $p[$key]=$Extra[$key] }
    return $p
}
function New-Plan([hashtable]$Extra=@{}) {
    $r = Invoke-Setup (Plan-Parameters $Extra)
    Assert-That ($r.Code -eq 0) $r.Output
    Assert-That (Test-Path -LiteralPath $script:planPath -PathType Leaf) 'Plan file was not created.'
    return (Read-Json $script:planPath)
}
function Apply-Parameters([hashtable]$Extra=@{}) {
    $p = Base-Parameters 'Apply'; $p.PlanPath=$script:planPath
    $p.ApprovedPlanSha256=(Get-FileHash -LiteralPath $script:planPath -Algorithm SHA256).Hash
    $p.JournalPath=$script:journalPath
    foreach ($key in $Extra.Keys) { $p[$key]=$Extra[$key] }
    return $p
}
function Invoke-Apply([hashtable]$Extra=@{}) { Invoke-Setup (Apply-Parameters $Extra) }
function Invoke-Journal([string]$Mode) {
    $p = Base-Parameters $Mode; $p.JournalPath=$script:journalPath; Invoke-Setup $p
}
function Assert-RefusedWithoutMutation($Result) {
    Assert-That ($Result.Code -ne 0) 'An unsafe request unexpectedly succeeded.'
    Assert-That (@(Get-Mutations).Count -eq 0) ('Refusal mutated the controlled device: ' + $Result.Output)
}
function Add-Fault([string]$Match, [string]$Mode='AfterCommitExit', [int]$Seconds=5) {
    Set-Fixture @{Faults=@([pscustomobject]@{Match=$Match; Mode=$Mode; Remaining=1; ExitCode=37; Seconds=$Seconds})}
}
function Initialize-Case {
    $script:caseNumber++
    $script:caseDir = Join-Path $testRoot ('case-' + $script:caseNumber)
    [void](New-Item -ItemType Directory -Path $script:caseDir)
    $script:statePath = Join-Path $script:caseDir 'state.json'
    $script:planPath = Join-Path $script:caseDir 'approved-plan.json'
    $script:journalPath = Join-Path $script:caseDir 'journal.json'
    $env:EVEBOX_TV_SETUP_TEST_STATE = $script:statePath
    $packages = [ordered]@{}
    foreach ($name in @($stock) + $optional + @($soft, 'com.example.unrelated')) {
        $packages[$name]=[ordered]@{Installed=$true; State=0; ComponentState=0; VersionCode=34; Hash=('d'*64)}
    }
    Write-Json $script:statePath ([ordered]@{Serial=$controlledSerial; Ready='device'; Qemu='0'; Model=$profile.match.model;
        Fingerprint=$profile.match.fingerprint; Sdk='34'; Abi='armeabi-v7a'; UserId='0'; CandidatePackage=$soft; CandidateVersion=35;
        CandidateHash=$apkHash; Cert=$cert; SignerExit=0; AaptExit=0; ExtraSigner=$false; PostInstallHashMismatch=$false;
        LaunchableActivity=($soft+'.MainActivity'); WrongForeground=$false; AutoHomeAfterStockDisable=''; Home=$oldHome; Foreground=$oldHome; Packages=$packages; Faults=@();
        Settings=[ordered]@{'secure/enabled_accessibility_services'='com.example.remote/com.example.remote.KeyService';
            'secure/accessibility_enabled'='1'; 'secure/sleep_timeout'='10800000'; 'system/screen_off_timeout'='10800000'}})
}
function Test-Case([string]$Name, [scriptblock]$Body) {
    if ($Case.Count -gt 0 -and $Name -notin $Case) { return }
    Initialize-Case
    $timer=[Diagnostics.Stopwatch]::StartNew()
    try {
        & $Body
        $script:results += [pscustomobject]@{Name=$Name; Passed=$true; Seconds=$timer.Elapsed.TotalSeconds}
        Write-Output ('PASS ' + $Name)
    } catch {
        $script:results += [pscustomobject]@{Name=$Name; Passed=$false; Seconds=$timer.Elapsed.TotalSeconds; Error=$_.Exception.Message}
        Write-Output ('FAIL ' + $Name + ': ' + $_.Exception.Message)
    } finally { $timer.Stop() }
}

try {
    Test-Case 'backend-honours-current-and-legacy-target-locks' {
        $null=New-Plan; Clear-Calls
        $hash=[Security.Cryptography.SHA256]::Create()
        try { $key=([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($controlledSerial+':5037')))).Replace('-','') }
        finally { $hash.Dispose() }
        foreach($prefix in @('Local\eve-box-tv-setup-','Local\softlumi-tv-setup-')) {
            $mutex=[Threading.Mutex]::new($false,($prefix+$key)); $owned=$false
            try {
                $owned=$mutex.WaitOne(0); Assert-That $owned 'Controlled lock fixture was unexpectedly held.'
                $r=Invoke-Apply; Assert-RefusedWithoutMutation $r
                Assert-That ($r.Output.Contains('another_setup_operation_is_running') -and @(Get-Calls).Count -eq 0) 'Apply bypassed an existing version of the target lock.'
                $r=Invoke-Journal 'Restore'; Assert-RefusedWithoutMutation $r
                Assert-That ($r.Output.Contains('another_setup_operation_is_running') -and @(Get-Calls).Count -eq 0) 'Restore bypassed an existing version of the target lock.'
            } finally { if($owned){$mutex.ReleaseMutex()};$mutex.Dispose() }
        }
        $r=Invoke-Apply; Assert-That ($r.Code -eq 0) ('Releasing the controlled locks did not permit Apply: '+$r.Output)
    }
    Test-Case 'legacy-plan-is-refused-before-adb' {
        $null=New-Plan
        $plan=Read-Json $planPath; $plan.tool='softlumi-tv-setup'; Write-Json $planPath $plan
        $before=[IO.File]::ReadAllText($planPath); Clear-Calls
        $r=Invoke-Apply; Assert-RefusedWithoutMutation $r
        Assert-That ($r.Output.Contains('legacy_document_requires_original_kit') -and @(Get-Calls).Count -eq 0) 'Legacy plan did not fail before any external tool.'
        Assert-That ([IO.File]::ReadAllText($planPath) -ceq $before -and !(Test-Path -LiteralPath $journalPath)) 'Legacy plan was changed or adopted.'
    }
    Test-Case 'legacy-journal-inspect-and-restore-refused-unchanged' {
        $null=New-Plan; $r=Invoke-Apply; Assert-That ($r.Code -eq 0) $r.Output
        $journal=Read-Json $journalPath; $journal.tool='softlumi-tv-setup'; Write-Json $journalPath $journal
        $before=[IO.File]::ReadAllText($journalPath); Clear-Calls
        foreach($mode in @('Inspect','Restore')) {
            $r=Invoke-Journal $mode; Assert-RefusedWithoutMutation $r
            Assert-That ($r.Output.Contains('legacy_document_requires_original_kit') -and @(Get-Calls).Count -eq 0) 'Legacy journal was queried as the new app.'
            Assert-That ([IO.File]::ReadAllText($journalPath) -ceq $before) 'Legacy journal bytes were changed.'
        }
    }
    Test-Case 'install-alongside-legacy-preserves-home-services-and-old-app' {
        $legacy='io.github.ekkzy.softlumi'; $legacyHome=$legacy+'/'+$legacy+'.MainActivity'
        $state=Get-Fixture
        $state.Packages | Add-Member -NotePropertyName $legacy -NotePropertyValue ([pscustomobject]@{Installed=$true;State=0;ComponentState=1;VersionCode=36;Hash=('e'*64)})
        $state.Home=$legacyHome; $state.Foreground=$legacyHome
        $state.Settings.'secure/enabled_accessibility_services'=$legacy+'/'+$legacy+'.RemoteAccessibilityService'
        Write-Json $statePath $state
        $oldApp=($state.Packages.PSObject.Properties[$legacy].Value | ConvertTo-Json -Compress)
        $settings=($state.Settings | ConvertTo-Json -Compress)
        $null=New-Plan; $r=Invoke-Apply; Assert-That ($r.Code -eq 0) $r.Output
        $after=Get-Fixture
        Assert-That ($after.Home -ceq $legacyHome -and ($after.Settings | ConvertTo-Json -Compress) -ceq $settings) 'Install-only changed legacy HOME or protected services.'
        Assert-That (($after.Packages.PSObject.Properties[$legacy].Value | ConvertTo-Json -Compress) -ceq $oldApp -and @(Get-PackageMutations $legacy).Count -eq 0) 'Legacy app changed during side-by-side install.'
        $r=Invoke-Journal 'Restore'; Assert-That ($r.Code -eq 0) $r.Output
        Assert-That ((Get-Fixture).Home -ceq $legacyHome -and @(Get-PackageMutations $legacy).Count -eq 0) 'Install-only restore changed the old app or HOME.'
    }
    Test-Case 'explicit-new-home-can-restore-legacy-home-without-modifying-old-app' {
        $legacy='io.github.ekkzy.softlumi'; $legacyHome=$legacy+'/'+$legacy+'.MainActivity'
        $state=Get-Fixture
        $state.Packages | Add-Member -NotePropertyName $legacy -NotePropertyValue ([pscustomobject]@{Installed=$true;State=0;ComponentState=1;VersionCode=36;Hash=('e'*64)})
        $state.Home=$legacyHome; $state.Foreground=$legacyHome; Write-Json $statePath $state
        $oldApp=($state.Packages.PSObject.Properties[$legacy].Value | ConvertTo-Json -Compress)
        $null=New-Plan @{ProfileId='mi-box-s3-android14';SetHome=$true}
        $r=Invoke-Apply; Assert-That ($r.Code -eq 0 -and (Get-Fixture).Home -ceq $softHome) $r.Output
        $r=Invoke-Journal 'Restore'; Assert-That ($r.Code -eq 0) $r.Output
        $after=Get-Fixture
        Assert-That ($after.Home -ceq $legacyHome -and ($after.Packages.PSObject.Properties[$legacy].Value | ConvertTo-Json -Compress) -ceq $oldApp -and @(Get-PackageMutations $legacy).Count -eq 0) 'Explicit HOME transition failed to restore the old HOME unchanged.'
    }
    Test-Case 'plan-is-read-only' {
        $before=Get-Content -LiteralPath $statePath -Raw
        $null=New-Plan
        Assert-That (@(Get-Mutations).Count -eq 0) 'Plan issued mutation or launch.'
        Assert-That ((Get-Content -LiteralPath $statePath -Raw) -ceq $before) 'Plan changed fixture state.'
        Assert-That (!(Test-Path -LiteralPath $journalPath)) 'Plan wrote a mutation journal.'
    }
    Test-Case 'plan-shows-current-brand-with-unchanged-profile' {
        $before=Get-Content -LiteralPath $statePath -Raw
        $r=Invoke-Setup (Plan-Parameters @{ProfileId='mi-box-s3-android14';SetHome=$true;DisableStockHome=$true})
        Assert-That ($r.Code -eq 0) $r.Output
        Assert-That ($r.Output.Contains('explicitly requested Eve Box Home plan') -and !$r.Output.Contains('explicitly requested evebox Home plan')) 'Plan showed the legacy product name.'
        $plan=Read-Json $planPath
        Assert-That ($plan.profile.sha256 -ceq 'e796f4de93e3f6e5db0cfaa961de35d19b5509890e1fbcdbd9dfe7fcd790c89c') 'Plan does not bind the current Eve Box profile.'
        Assert-That (@(Get-Mutations).Count -eq 0 -and (Get-Content -LiteralPath $statePath -Raw) -ceq $before -and !(Test-Path -LiteralPath $journalPath)) 'Display formatting changed read-only Plan behavior.'
    }
    Test-Case 'explicit-target-required' {
        $p=Plan-Parameters; $p.Remove('Serial'); $r=Invoke-Setup $p
        Assert-RefusedWithoutMutation $r
        Assert-That (@(Get-Calls).Count -eq 0) 'Missing target invoked tools.'
    }
    Test-Case 'target-mismatch-refused' {
        $r=Invoke-Setup (Plan-Parameters @{ExpectedModel='Wrong TV'})
        Assert-RefusedWithoutMutation $r
    }
    Test-Case 'secondary-user-and-disabled-launcher-refused' {
        Set-Fixture @{UserId='1'}
        Assert-RefusedWithoutMutation (Invoke-Setup (Plan-Parameters))
        Set-Fixture @{UserId='0'}; Set-PackageState $soft 2
        Assert-RefusedWithoutMutation (Invoke-Setup (Plan-Parameters))
        Set-PackageState $soft 0
        $state=Get-Fixture; $state.Packages.PSObject.Properties[$soft].Value.ComponentState=2; Write-Json $statePath $state
        Assert-RefusedWithoutMutation (Invoke-Setup (Plan-Parameters))
    }
    Test-Case 'unknown-firmware-cannot-disable' {
        $unknown='Unknown/vendor/device:14/test/firmware:user/release-keys'; Set-Fixture @{Fingerprint=$unknown}
        $r=Invoke-Setup (Plan-Parameters @{ExpectedFingerprint=$unknown; ProfileId='mi-box-s3-android14'; SetHome=$true; DisableStockHome=$true})
        Assert-RefusedWithoutMutation $r
    }
    Test-Case 'generic-profile-cannot-disable' {
        $r=Invoke-Setup (Plan-Parameters @{DisablePackages=@($optional[0])})
        Assert-RefusedWithoutMutation $r
    }
    Test-Case 'apk-hash-refused-before-adb' {
        $r=Invoke-Setup (Plan-Parameters @{ApkSha256=('f'*64)})
        Assert-RefusedWithoutMutation $r
        Assert-That (@(Get-Calls | Where-Object Tool -eq 'adb').Count -eq 0) 'Hash mismatch reached ADB.'
    }
    Test-Case 'certificate-refused-before-adb' {
        $r=Invoke-Setup (Plan-Parameters @{SigningCertSha256=('f'*64)})
        Assert-RefusedWithoutMutation $r
        Assert-That (@(Get-Calls | Where-Object Tool -eq 'adb').Count -eq 0) 'Certificate mismatch reached ADB.'
    }
    Test-Case 'package-and-multiple-signers-refused' {
        Set-Fixture @{CandidatePackage='com.example.wrong'}
        Assert-RefusedWithoutMutation (Invoke-Setup (Plan-Parameters))
        Set-Fixture @{CandidatePackage=$soft; ExtraSigner=$true}
        Assert-RefusedWithoutMutation (Invoke-Setup (Plan-Parameters))
    }
    Test-Case 'leanback-metadata-rejects-wrong-main-before-adb' {
        Set-Fixture @{LaunchableActivity='io.github.ekkzy.evebox.OtherActivity'}
        Assert-RefusedWithoutMutation (Invoke-Setup (Plan-Parameters))
        Assert-That (@(Get-Calls | Where-Object Tool -eq 'adb').Count -eq 0) 'Wrong leanback Activity reached ADB.'
    }
    Test-Case 'no-downgrade' {
        Set-Fixture @{CandidateVersion=33}
        Assert-RefusedWithoutMutation (Invoke-Setup (Plan-Parameters))
    }
    Test-Case 'approved-plan-hash-mismatch' {
        $null=New-Plan; Clear-Calls
        Assert-RefusedWithoutMutation (Invoke-Apply @{ApprovedPlanSha256=('f'*64)})
        Assert-That (!(Test-Path -LiteralPath $journalPath)) 'Bad approval wrote a mutation journal.'
    }
    Test-Case 'expired-plan-refused' {
        $p=New-Plan; $expiredStart=[DateTime]::UtcNow.AddHours(-2); $p.createdUtc=$expiredStart.ToString('o'); $p.expiresUtc=$expiredStart.AddMinutes(30).ToString('o')
        Write-Json $planPath $p; Clear-Calls
        Assert-RefusedWithoutMutation (Invoke-Apply)
    }
    Test-Case 'before-state-drift-refused' {
        $null=New-Plan @{ProfileId='mi-box-s3-android14'; DisablePackages=@($optional[0])}
        Set-PackageState $optional[0] 1; Clear-Calls
        Assert-RefusedWithoutMutation (Invoke-Apply)
    }
    Test-Case 'protected-service-state-drift-refused' {
        $null=New-Plan
        $state=Get-Fixture; $state.Settings.PSObject.Properties['secure/enabled_accessibility_services'].Value='com.example.other/com.example.other.Remote'
        Write-Json $statePath $state; Clear-Calls
        Assert-RefusedWithoutMutation (Invoke-Apply)
    }
    Test-Case 'full-apply-verifies-exact-installed-hash' {
        $null=New-Plan; Clear-Calls; $r=Invoke-Apply
        Assert-That ($r.Code -eq 0) $r.Output
        Assert-That ((Read-Json $journalPath).status -eq 'Applied') 'Apply did not finish its journal.'
        $state=Get-Fixture; Assert-That ($state.Packages.PSObject.Properties[$soft].Value.Hash -eq $apkHash) 'Wrong installed APK bytes.'
        Assert-That ($state.Home -eq $oldHome) 'Install-only changed HOME.'
        Assert-That (@(Get-Calls | Where-Object { ($_.Args -join ' ') -match 'shell sha256sum /data/app/' }).Count -gt 0) 'No installed APK hash verification.'
        Assert-That (@(Get-PackageMutations $stock).Count -eq 0) 'Install-only changed stock package.'
    }
    Test-Case 'upgrade-existing-evebox-home-is-inspectable-and-restorable' {
        Set-Fixture @{Home=$softHome;Foreground=$softHome}
        $null=New-Plan; $r=Invoke-Apply; Assert-That ($r.Code -eq 0) $r.Output
        Clear-Calls; $r=Invoke-Journal 'Inspect'; Assert-That ($r.Code -eq 0) $r.Output
        $inspection=($r.Output -split 'READ-ONLY INSPECTION:')[0] | ConvertFrom-Json
        Assert-That ($inspection.canRestore -and @($inspection.conflicts).Count -eq 0) 'An owned evebox upgrade was misclassified as foreign HOME drift.'
        Assert-That (@(Get-Mutations).Count -eq 0) 'Upgrade inspection mutated the device.'
        Clear-Calls; $r=Invoke-Journal 'Restore'; Assert-That ($r.Code -eq 0) $r.Output
        $state=Get-Fixture
        Assert-That ($state.Home -ceq $softHome -and $state.Packages.PSObject.Properties[$soft].Value.Hash -ceq $apkHash -and $state.Packages.PSObject.Properties[$soft].Value.VersionCode -eq 35) 'Restore changed the existing evebox HOME or rolled back the updated APK.'
        Assert-That (@(Get-Mutations).Count -eq 0) 'An install-only restore issued device mutations.'
    }
    Test-Case 'installed-hash-mismatch-needs-reconcile' {
        $null=New-Plan; Set-Fixture @{PostInstallHashMismatch=$true}; Clear-Calls
        $r=Invoke-Apply; Assert-That ($r.Code -ne 0) 'Mismatched installed bytes succeeded.'
        Assert-That ((Read-Json $journalPath).status -eq 'NeedsReconcile') 'Unverified install was not marked uncertain.'
        Assert-That (@(Get-PackageMutations $stock).Count -eq 0) 'Unverified install disabled stock HOME.'
    }
    Test-Case 'restore-exact-states-0-through-4-and-home-order' {
        for($i=0;$i -lt 5;$i++){Set-PackageState $optional[$i] $i}
        $null=New-Plan @{ProfileId='mi-box-s3-android14'; SetHome=$true; DisableStockHome=$true; DisablePackages=@($optional[0..4])}
        Clear-Calls; $r=Invoke-Apply; Assert-That ($r.Code -eq 0) $r.Output
        $state=Get-Fixture
        Assert-That ($state.Home -eq $softHome) 'Requested evebox HOME was not verified.'
        for($i=2;$i -lt 5;$i++){
            Assert-That ($state.Packages.PSObject.Properties[$optional[$i]].Value.State -eq $i) 'Apply changed a pre-disabled package override.'
            Assert-That (@(Get-PackageMutations $optional[$i]).Count -eq 0) 'Apply touched a pre-disabled package.'
        }
        # A NoOp is not ownership: a later external override must be refused,
        # even though the same package was listed in the approved selection.
        Set-PackageState $optional[3] 4; Clear-Calls
        Assert-RefusedWithoutMutation (Invoke-Journal 'Restore')
        Set-PackageState $optional[3] 3
        Clear-Calls; $r=Invoke-Journal 'Restore'; Assert-That ($r.Code -eq 0) $r.Output
        $state=Get-Fixture
        for($i=0;$i -lt 5;$i++){Assert-That ($state.Packages.PSObject.Properties[$optional[$i]].Value.State -eq $i) ('Incorrect restored override '+$i)}
        for($i=2;$i -lt 5;$i++){Assert-That (@(Get-PackageMutations $optional[$i]).Count -eq 0) 'Restore issued a command for an untouched NoOp package.'}
        $restoreVerbs=@('default-state','enable','disable','disable-user','disable-until-used')
        foreach($i in @(0,1)){
            $expected='shell pm '+$restoreVerbs[$i]+' --user 0 '+[regex]::Escape($optional[$i])+'$'
            Assert-That (@(Get-PackageMutations $optional[$i] | Where-Object {($_.Args -join ' ') -match $expected}).Count -eq 1) ('Restore did not use the exact override command for state '+$i)
        }
        Assert-That ($state.Home -eq $oldHome) 'Original HOME was not restored.'
        Assert-That ($state.Packages.PSObject.Properties[$soft].Value.Hash -eq $apkHash) 'Restore downgraded or removed the updated app.'
        $calls=@(Get-Mutations | ForEach-Object { $_.Args -join ' ' })
        $enable=-1; $homeCallIndex=-1
        for($i=0;$i -lt $calls.Count;$i++){
            if($calls[$i] -match ('shell pm default-state --user 0 '+[regex]::Escape($stock)+'$')){$enable=$i}
            if($calls[$i] -match ('set-home-activity --user 0 '+[regex]::Escape($oldHome)+'$')){$homeCallIndex=$i}
        }
        Assert-That ($enable -ge 0 -and $homeCallIndex -gt $enable) 'Restore assigned old HOME before re-enabling its package.'
    }
    Test-Case 'inspect-does-not-mutate-device-or-journal' {
        $null=New-Plan; $r=Invoke-Apply; Assert-That ($r.Code -eq 0) $r.Output
        $before=Get-Content -LiteralPath $journalPath -Raw; $state=Get-Content -LiteralPath $statePath -Raw
        Clear-Calls; $r=Invoke-Journal 'Inspect'; Assert-That ($r.Code -eq 0) $r.Output
        Assert-That (@(Get-Mutations).Count -eq 0) 'Inspect mutated the device.'
        Assert-That ((Get-Content -LiteralPath $journalPath -Raw) -ceq $before) 'Inspect rewrote the journal.'
        Assert-That ((Get-Content -LiteralPath $statePath -Raw) -ceq $state) 'Inspect changed package state.'
    }
    Test-Case 'powershell7-journal-is-inspected-and-restored-by-windows-powershell51' {
        $ps7=(Get-Command pwsh.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
        $ps51=(Get-Command powershell.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
        $r=Invoke-Setup (Plan-Parameters @{ProfileId='mi-box-s3-android14';SetHome=$true;DisableStockHome=$true;DisablePackages=@($optional[0])}) $ps7
        Assert-That ($r.Code -eq 0) $r.Output
        $r=Invoke-Setup (Apply-Parameters) $ps7; Assert-That ($r.Code -eq 0) $r.Output
        $saved=Get-Content -LiteralPath $journalPath -Raw; Clear-Calls
        $p=Base-Parameters 'Inspect';$p.JournalPath=$journalPath
        $r=Invoke-Setup $p $ps51; Assert-That ($r.Code -eq 0) $r.Output
        Assert-That (@(Get-Mutations).Count -eq 0) 'Cross-runtime Inspect mutated the device.'
        Assert-That ((Get-Content -LiteralPath $journalPath -Raw) -ceq $saved) 'Cross-runtime Inspect rewrote the journal.'
        Clear-Calls; $p.Mode='Restore'; $r=Invoke-Setup $p $ps51; Assert-That ($r.Code -eq 0) $r.Output
        $state=Get-Fixture
        Assert-That ($state.Home -ceq $oldHome -and $state.Packages.PSObject.Properties[$stock].Value.State -eq 0 -and $state.Packages.PSObject.Properties[$optional[0]].Value.State -eq 0) 'Cross-runtime Restore did not recover the original HOME/package states.'
        Assert-That ((Read-Json $journalPath).status -ceq 'Restored') 'Cross-runtime journal was not completed.'
    }
    Test-Case 'transport-error-after-install-commit-is-terminal-unknown' {
        $null=New-Plan @{ProfileId='mi-box-s3-android14';SetHome=$true;DisableStockHome=$true}
        Add-Fault '^install -r --user 0 '; Clear-Calls
        $r=Invoke-Apply; Assert-That ($r.Code -ne 0) 'Transport error succeeded.'
        Assert-That ((Read-Json $journalPath).status -eq 'NeedsReconcile') 'Committed/unknown install was not preserved.'
        Assert-That ((Get-Fixture).Packages.PSObject.Properties[$soft].Value.Hash -eq $apkHash) 'Fault did not actually commit before failing.'
        Assert-That (@(Get-PackageMutations $stock).Count -eq 0) 'Unknown install continued with stock disable.'
        Clear-Calls; $before=Get-Content -LiteralPath $journalPath -Raw; $inspection=Invoke-Journal 'Inspect'
        Assert-That ($inspection.Code -eq 0) $inspection.Output
        $inspectionJson=($inspection.Output -split 'READ-ONLY INSPECTION:')[0] | ConvertFrom-Json
        Assert-That ($inspectionJson.installOutcomeUnknown -and !$inspectionJson.canRestore) 'Inspection treated an unknown install as safely reversible.'
        Assert-That (@(Get-Mutations).Count -eq 0) 'Inspect retried an uncertain install.'
        Assert-That ((Get-Content -LiteralPath $journalPath -Raw) -ceq $before) 'Inspect changed the uncertain journal.'
        Clear-Calls; Assert-RefusedWithoutMutation (Invoke-Journal 'Restore')
    }
    Test-Case 'partial-package-commit-is-reconciled-and-restored' {
        $null=New-Plan @{ProfileId='mi-box-s3-android14';DisablePackages=@($optional[0])}
        Add-Fault ('^shell pm disable-user --user 0 '+[regex]::Escape($optional[0])+'$'); Clear-Calls
        $r=Invoke-Apply; Assert-That ($r.Code -ne 0) 'After-commit package fault succeeded.'
        Assert-That ((Read-Json $journalPath).status -eq 'NeedsReconcile') 'Partial transaction lost its uncertainty.'
        Assert-That ((Get-Fixture).Packages.PSObject.Properties[$optional[0]].Value.State -eq 3) 'Fault did not commit package state.'
        Clear-Calls; $r=Invoke-Journal 'Restore'; Assert-That ($r.Code -eq 0) $r.Output
        Assert-That ((Get-Fixture).Packages.PSObject.Properties[$optional[0]].Value.State -eq 0) 'Pending committed package was not restored.'
        Assert-That (@(Get-PackageMutations $optional[1]).Count -eq 0) 'Restore touched an unrelated package.'
    }
    Test-Case 'pending-package-not-committed-is-not-mutated-by-restore' {
        $null=New-Plan @{ProfileId='mi-box-s3-android14';DisablePackages=@($optional[0])}
        Add-Fault ('^shell pm disable-user --user 0 '+[regex]::Escape($optional[0])+'$') 'BeforeExit'; Clear-Calls
        $r=Invoke-Apply; Assert-That ($r.Code -ne 0) 'Before-commit transport fault succeeded.'
        Assert-That ((Read-Json $journalPath).status -eq 'NeedsReconcile') 'Pending operation was not journaled.'
        Assert-That ((Get-Fixture).Packages.PSObject.Properties[$optional[0]].Value.State -eq 0) 'Before-commit fixture unexpectedly changed state.'
        Clear-Calls; $r=Invoke-Journal 'Restore'; Assert-That ($r.Code -eq 0) $r.Output
        Assert-That (@(Get-PackageMutations $optional[0]).Count -eq 0) 'Already-before pending package was unnecessarily mutated.'
    }
    Test-Case 'owned-stock-disable-fallback-is-captured-and-restored' {
        $third='com.example.unrelated/com.example.unrelated.Main'
        Set-Fixture @{AutoHomeAfterStockDisable=$third}
        $null=New-Plan @{ProfileId='mi-box-s3-android14';SetHome=$true;DisableStockHome=$true}
        Clear-Calls; $r=Invoke-Apply; Assert-That ($r.Code -eq 0) $r.Output
        $journal=Read-Json $journalPath
        Assert-That ($journal.ownedHomeFallback -ceq $third) 'Successful stock disable did not bind its exact automatic fallback.'
        Assert-That ((Get-Fixture).Home -ceq $softHome) 'Captured fallback prevented the requested evebox HOME transition.'
        Clear-Calls; $r=Invoke-Journal 'Restore'; Assert-That ($r.Code -eq 0) $r.Output
        Assert-That ((Get-Fixture).Home -ceq $oldHome) 'Original HOME was not restored after a captured automatic fallback.'
        Assert-That ((Get-Fixture).Packages.PSObject.Properties[$stock].Value.State -eq 0) 'Original stock HOME package was not restored.'
    }
    Test-Case 'unknown-stock-disable-restores-package-without-overriding-foreign-home' {
        $third='com.example.unrelated/com.example.unrelated.Main'
        Set-Fixture @{AutoHomeAfterStockDisable=$third}
        $null=New-Plan @{ProfileId='mi-box-s3-android14';SetHome=$true;DisableStockHome=$true}
        Add-Fault ('^shell pm disable-user --user 0 '+[regex]::Escape($stock)+'$'); Clear-Calls
        $r=Invoke-Apply; Assert-That ($r.Code -ne 0) 'Unknown stock-disable outcome was accepted.'
        Assert-That ((Read-Json $journalPath).status -ceq 'NeedsReconcile') 'Unknown stock-disable outcome lost its journal.'
        Assert-That ((Get-Fixture).Packages.PSObject.Properties[$stock].Value.State -eq 3) 'After-commit fault did not disable the original HOME package.'
        Clear-Calls; $r=Invoke-Journal 'Restore'
        Assert-That ($r.Code -ne 0) 'Restore silently adopted an uncaptured foreign HOME.'
        $state=Get-Fixture
        Assert-That ($state.Packages.PSObject.Properties[$stock].Value.State -eq 0) 'Restore left its own original HOME package disabled after an uncertain commit.'
        Assert-That ($state.Home -ceq $third) 'Restore replaced a foreign HOME without an owned fallback record.'
        Assert-That (@(Get-Mutations | Where-Object {($_.Args -join ' ') -match 'set-home-activity'}).Count -eq 0) 'Partial restoration issued an unauthorized HOME selection.'
        Assert-That (@(Get-PackageMutations $optional[0]).Count -eq 0) 'Partial restoration touched an unrelated package.'
    }
    Test-Case 'restore-refuses-later-unrelated-home-change' {
        $null=New-Plan @{ProfileId='mi-box-s3-android14';SetHome=$true}; $r=Invoke-Apply; Assert-That ($r.Code -eq 0) $r.Output
        Set-Fixture @{Home='com.example.unrelated/com.example.unrelated.Main'}; Clear-Calls
        Assert-RefusedWithoutMutation (Invoke-Journal 'Restore')
    }
    Test-Case 'restore-refuses-later-package-state-change' {
        $null=New-Plan @{ProfileId='mi-box-s3-android14';DisablePackages=@($optional[0])}; $r=Invoke-Apply; Assert-That ($r.Code -eq 0) $r.Output
        Set-PackageState $optional[0] 2; Clear-Calls
        Assert-RefusedWithoutMutation (Invoke-Journal 'Restore')
    }
    Test-Case 'wrong-foreground-forbids-any-disable' {
        $null=New-Plan @{ProfileId='mi-box-s3-android14';SetHome=$true;DisableStockHome=$true}
        Set-Fixture @{WrongForeground=$true}; Clear-Calls
        $r=Invoke-Apply; Assert-That ($r.Code -ne 0) 'Wrong foreground was accepted.'
        Assert-That (@(Get-PackageMutations $stock).Count -eq 0) 'Stock HOME was disabled without usable evebox foreground.'
    }
    Test-Case 'existing-journal-is-never-overwritten' {
        $null=New-Plan; $r=Invoke-Apply; Assert-That ($r.Code -eq 0) $r.Output
        $before=Get-Content -LiteralPath $journalPath -Raw; Clear-Calls
        Assert-RefusedWithoutMutation (Invoke-Apply)
        Assert-That ((Get-Content -LiteralPath $journalPath -Raw) -ceq $before) 'Existing journal was overwritten.'
    }
    Test-Case 'literal-path-and-unsafe-arguments-cannot-execute-code' {
        $sentinel=Join-Path $caseDir 'injected-marker.txt'
        $bad='controlled-tv; New-Item -ItemType File -Path '+$sentinel
        Assert-RefusedWithoutMutation (Invoke-Setup (Plan-Parameters @{Serial=$bad}))
        Assert-RefusedWithoutMutation (Invoke-Setup (Plan-Parameters @{ProfileId='..\..\outside'}))
        Assert-RefusedWithoutMutation (Invoke-Setup (Plan-Parameters @{ProfileId='mi-box-s3-android14';DisablePackages=@('com.netflix.ninja;echo unsafe')}))
        Assert-That (!(Test-Path -LiteralPath $sentinel)) 'Untrusted argument executed as shell text.'
        Clear-Calls; $null=New-Plan; $r=Invoke-Apply; Assert-That ($r.Code -eq 0) $r.Output
        Assert-That ((Get-Fixture).Packages.PSObject.Properties[$soft].Value.Hash -eq $apkHash) 'Literal APK path containing ampersand/spaces was misparsed.'
    }
    Test-Case 'command-timeout-is-bounded-without-mutation' {
        Add-Fault '^get-state$' 'TimeoutBefore' 6
        $r=Invoke-Setup (Plan-Parameters @{CommandTimeoutSeconds=1})
        Assert-RefusedWithoutMutation $r
        Assert-That ($r.Seconds -lt 9) ('One-second command timeout was not bounded: '+$r.Seconds)
    }
    Test-Case 'install-timeout-after-commit-needs-reconcile' {
        $null=New-Plan @{ProfileId='mi-box-s3-android14';SetHome=$true;DisableStockHome=$true}
        Add-Fault '^install -r --user 0 ' 'TimeoutAfter' 6; Clear-Calls
        $r=Invoke-Apply @{InstallTimeoutSeconds=1}
        Assert-That ($r.Code -ne 0 -and $r.Seconds -lt 15) 'Install timeout did not terminate the operation within its bound.'
        Assert-That ((Read-Json $journalPath).status -eq 'NeedsReconcile') 'Timed-out install lost uncertainty.'
        Assert-That ((Get-Fixture).Packages.PSObject.Properties[$soft].Value.Hash -eq $apkHash) 'Timeout fixture did not commit before blocking.'
        Assert-That (@(Get-PackageMutations $stock).Count -eq 0) 'Timed-out install continued with disabling HOME.'
        Clear-Calls; Assert-RefusedWithoutMutation (Invoke-Journal 'Restore')
    }
    $failed=@($results | Where-Object { !$_.Passed }).Count
    Assert-That ($results.Count -gt 0) 'No test case matched the requested filter.'
    $summary=[ordered]@{SchemaVersion=1;PowerShellVersion=$PSVersionTable.PSVersion.ToString();OfflineOnly=$true;
        BackendCallDeadlineSeconds=50;BackendCallCount=$backendCallDurations.Count;MaximumBackendCallSeconds=($backendCallDurations | Measure-Object -Maximum).Maximum;BackendSha256=[ordered]@{Entry=(Get-FileHash -LiteralPath $setup -Algorithm SHA256).Hash.ToLowerInvariant();Common=(Get-FileHash -LiteralPath (Join-Path $PSScriptRoot 'TvSetup.Common.psm1') -Algorithm SHA256).Hash.ToLowerInvariant()};CaseCount=$results.Count;Passed=@($results | Where-Object Passed).Count;Failed=$failed;Results=$results}
    if($ReportPath){$report=[IO.Path]::GetFullPath($ReportPath);$directory=[IO.Path]::GetDirectoryName($report);[void](New-Item -ItemType Directory -Path $directory -Force);Write-Json $report $summary}
    Write-Output ('TV setup controlled tests: '+$summary.Passed+' passed, '+$failed+' failed. No actual Android tool or device was used.')
    if($KeepFixtures){Write-Output ('Controlled fixtures retained: '+$testRoot)}
    if($failed -gt 0){exit 1};exit 0
} finally {
    $env:EVEBOX_TV_SETUP_TEST_STATE=$oldState; $env:EVEBOX_TV_SETUP_TEST_ROLE=$oldRole; $env:EVEBOX_TV_SETUP_TEST_MARKER=$oldMarker
    if(!$KeepFixtures){
        $resolved=[IO.Path]::GetFullPath($testRoot)
        $prefix=[IO.Path]::GetFullPath($PSScriptRoot)+[IO.Path]::DirectorySeparatorChar
        if(!$resolved.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or
            !(Test-Path -LiteralPath (Join-Path $resolved '.owned-fixture')) -or
            [IO.File]::ReadAllText((Join-Path $resolved '.owned-fixture')) -cne $marker){throw 'Refusing cleanup outside the verified owned fixture.'}
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
