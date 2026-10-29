#requires -Version 5.1
<# .SYNOPSIS
Offline one-command installer tests. Reads an exact signed release APK but runs
only controlled PowerShell ADB doubles. No Android device, network or SDK runs.
#>
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$PinnedApk, [string]$LegacyPinnedApk='',
      [string]$ReportPath='', [string[]]$Case=@(), [switch]$KeepFixtures)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$hostExe=(Get-Process -Id $PID).Path
$entry=Join-Path $PSScriptRoot 'setup-eve-box.ps1'
$backend=Join-Path $PSScriptRoot 'setup-tv.ps1'
$wizardDriver=Join-Path $PSScriptRoot 'tests/eve-setup-driver.ps1'
$backendDriver=Join-Path $PSScriptRoot 'tests/tv-setup-driver.ps1'
$adbDouble=Join-Path $PSScriptRoot 'tests/eve-setup-adb-stub.ps1'
$delegate=Join-Path $PSScriptRoot 'tests/tv-setup-tool-stub.ps1'
$expectedApkHash='05acbffb15ed3234d42720b69d26d237df66fd22c775686ab313240fbdb04d98'
$apkName='Eve-Box-0.3.0-candidate.15.apk'
$soft='io.github.ekkzy.softlumi'; $stock='com.google.android.apps.tv.launcherx'
$softComponent=$soft+'/'+$soft+'.MainActivity'
$stockComponent=$stock+'/'+$stock+'.home.HomeActivity'
function Assert-That([bool]$Condition,[string]$Message){if(!$Condition){throw $Message}}
function Digest([string]$Path){
    $stream=[IO.File]::OpenRead($Path);$hash=[Security.Cryptography.SHA256]::Create()
    try{return ([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}
    finally{$hash.Dispose();$stream.Dispose()}
}
function Write-Json([string]$Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 30),[Text.UTF8Encoding]::new($false))}
function Read-Json([string]$Path){Get-Content -LiteralPath $Path -Raw -Encoding UTF8|ConvertFrom-Json}
function Source-Hashes{
    $root=Split-Path -Parent $PSScriptRoot;$hashes=[ordered]@{}
    foreach($relative in @('install.cmd','scripts/setup-eve-box.ps1','scripts/EveSetup.Wizard.psm1','scripts/setup-tv.ps1','scripts/TvSetup.Common.psm1',
        'scripts/setup-profiles/generic-install-only.json','scripts/setup-profiles/mi-box-s3-android14.json','scripts/test-eve-setup.ps1',
        'scripts/tests/eve-setup-driver.ps1','scripts/tests/eve-setup-adb-stub.ps1','scripts/tests/tv-setup-driver.ps1','scripts/tests/tv-setup-tool-stub.ps1')){
        $hashes[$relative]=Digest (Join-Path $root $relative)
    }
    return $hashes
}
foreach($path in @($entry,$backend,$wizardDriver,$backendDriver,$adbDouble,$delegate)){
    Assert-That (Test-Path -LiteralPath $path -PathType Leaf) ('Required test input is not ready: '+$path)
}
$sourceStartSha256=Source-Hashes
$sourceApk=(Get-Item -LiteralPath $PinnedApk -ErrorAction Stop).FullName
Assert-That ((Get-Item -LiteralPath $sourceApk).Length -eq 47129158 -and (Digest $sourceApk) -ceq $expectedApkHash) 'Supply the exact signed candidate.15 APK36; no synthetic APK can exercise pinned verification.'
$legacyApkHash='9f6787ba2badeb63c067eabc1153e2545773cadd9adfe024c5e1eaa6a5f556a6'
$legacyApkSource=''
if($LegacyPinnedApk){
    $legacyApkSource=(Get-Item -LiteralPath $LegacyPinnedApk -ErrorAction Stop).FullName
    Assert-That ((Get-Item -LiteralPath $legacyApkSource).Length -eq 47030664 -and (Digest $legacyApkSource) -ceq $legacyApkHash) 'Supply the exact signed legacy candidate.14 APK35 for backward compatibility verification.'
}
$profile=Read-Json (Join-Path $PSScriptRoot 'setup-profiles/mi-box-s3-android14.json')
$optional=@($profile.packages|Where-Object role -eq 'optional'|ForEach-Object packageName)
$testRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot ('.eve-setup-test-'+[guid]::NewGuid().ToString('N'))))
[void][IO.Directory]::CreateDirectory($testRoot)
$marker=[guid]::NewGuid().ToString('N')
$selectedSerial='controlled-tv-'+$marker.Substring(0,12)+':5555'
$otherSerial='controlled-other-'+$marker.Substring(0,12)+':5555'
[IO.File]::WriteAllText((Join-Path $testRoot '.owned-fixture'),$marker)
$toolsRoot=Join-Path $testRoot 'controlled tools';[void][IO.Directory]::CreateDirectory($toolsRoot)
$adbPath=Join-Path $toolsRoot 'adb controlled.ps1'
[IO.File]::WriteAllText($adbPath,('& '''+$adbDouble.Replace("'","''")+''' @args'+"`nexit `$LASTEXITCODE`n"),[Text.UTF8Encoding]::new($false))
foreach($toolName in @('adb','aapt','apksigner')){
    [IO.File]::WriteAllText((Join-Path $toolsRoot ($toolName+'.cmd')), "@echo controlled SDK invocation> `"%~dp0sdk-tripwire.txt`"`r`n@exit /b 91`r`n",[Text.Encoding]::ASCII)
}
$environmentNames=@('SOFTLUMI_TV_SETUP_TEST_MARKER','SOFTLUMI_TV_SETUP_TEST_STATE','SOFTLUMI_TV_SETUP_TEST_ROLE','SOFTLUMI_EVE_SETUP_TEST_DELEGATE')
$previousEnvironment=@{}
foreach($name in $environmentNames){$previousEnvironment[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$env:SOFTLUMI_TV_SETUP_TEST_MARKER='isolated-offline-fixture-v1'
$env:SOFTLUMI_EVE_SETUP_TEST_DELEGATE=$delegate
$results=@();$caseNumber=0;$childTimes=@()

function Get-Fixture{Read-Json $script:statePath}
function Set-Fixture([hashtable]$Changes){
    $state=Get-Fixture;foreach($key in $Changes.Keys){$state.$key=$Changes[$key]};Write-Json $script:statePath $state
}
function Get-Calls{
    if(Test-Path -LiteralPath ($script:statePath+'.calls')){Get-Content -LiteralPath ($script:statePath+'.calls')|ForEach-Object{$_|ConvertFrom-Json}}
}
function Clear-Calls{if(Test-Path -LiteralPath ($script:statePath+'.calls')){Remove-Item -LiteralPath ($script:statePath+'.calls') -Force}}
function Get-Mutations{
    Get-Calls|Where-Object{$_.Tool -eq 'adb' -and $_.Args.Count -ge 5 -and $_.Args[2] -ceq '-s' -and
        (($_.Args|Select-Object -Skip 4)-join ' ') -match '^(install |shell (?:pm (?:enable|disable|disable-user|disable-until-used|default-state) |cmd package set-home-activity |am start ))'}
}
function Get-Installs{Get-Mutations|Where-Object{(($_.Args|Select-Object -Skip 4)-join ' ') -match '^install '}}
function Get-PackageMutations{Get-Mutations|Where-Object{(($_.Args|Select-Object -Skip 4)-join ' ') -match '^shell pm '}}
function Get-Plans{@(Get-ChildItem -LiteralPath $script:kitRoot -Filter 'setup-plan.json' -File -Recurse)}
function Get-Journals{@(Get-ChildItem -LiteralPath $script:kitRoot -Filter 'setup-journal.json' -File -Recurse)}
function Answers{
    if(Test-Path -LiteralPath ($script:statePath+'.answers')){Get-Content -LiteralPath ($script:statePath+'.answers')|ForEach-Object{$_|ConvertFrom-Json}}
}
function Quote-Argument([string]$Value){
    $escaped=[regex]::Replace($Value,'(\\*)"','$1$1\"');$escaped=[regex]::Replace($escaped,'(\\+)$','$1$1')
    return '"'+$escaped+'"'
}
function Invoke-Controlled([hashtable]$Parameters,[object[]]$Responses=@(),[Alias('Backend')][switch]$UseBackend,[scriptblock]$WhileRunning=$null){
    $parameterFile=Join-Path $script:caseDir ('arguments-'+[guid]::NewGuid().ToString('N')+'.json');Write-Json $parameterFile $Parameters
    $responseFile=Join-Path $script:caseDir ('responses-'+[guid]::NewGuid().ToString('N')+'.json');Write-Json $responseFile @{responses=@($Responses)}
    $arguments=@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File')
    if($UseBackend){$arguments+=@($backendDriver,'-ScriptPath',$backend,'-ParametersPath',$parameterFile)}
    else{$arguments+=@($wizardDriver,'-ParametersPath',$parameterFile,'-ResponsesPath',$responseFile)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=[Diagnostics.ProcessStartInfo]::new()
    $process.StartInfo.FileName=$hostExe;$process.StartInfo.UseShellExecute=$false;$process.StartInfo.CreateNoWindow=$true
    $process.StartInfo.RedirectStandardOutput=$true;$process.StartInfo.RedirectStandardError=$true
    $process.StartInfo.StandardOutputEncoding=[Text.UTF8Encoding]::new($false)
    $process.StartInfo.StandardErrorEncoding=[Text.UTF8Encoding]::new($false)
    $process.StartInfo.Arguments=(@($arguments|ForEach-Object{Quote-Argument $_})-join ' ')
    # A regression must not find a real SDK on the host. Every Android command
    # is either the addressed PS double or an explicit failing tripwire.
    $process.StartInfo.EnvironmentVariables['PATH']=$toolsRoot+';'+(Join-Path $env:SystemRoot 'System32')
    if([IO.Path]::GetFileName($hostExe) -ieq 'powershell.exe'){
        $process.StartInfo.EnvironmentVariables['PSModulePath']=(Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/Modules')+';'+(Join-Path $env:ProgramFiles 'WindowsPowerShell/Modules')
    }
    $timer=[Diagnostics.Stopwatch]::StartNew()
    try{
        [void]$process.Start();$stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        if($null -ne $WhileRunning){& $WhileRunning}
        if(!$process.WaitForExit(70000)){
            $killer=[Diagnostics.Process]::new();$killer.StartInfo=[Diagnostics.ProcessStartInfo]::new()
            $killer.StartInfo.FileName=Join-Path $env:SystemRoot 'System32/taskkill.exe';$killer.StartInfo.Arguments='/PID '+$process.Id+' /T /F'
            $killer.StartInfo.UseShellExecute=$false;$killer.StartInfo.CreateNoWindow=$true
            [void]$killer.Start();if(!$killer.WaitForExit(4000)){$killer.Kill()};$killer.Dispose()
            if(!$process.WaitForExit(2000)){$process.Kill()}
            throw 'Controlled child exceeded its independent70s deadline; not an expected refusal.'
        }
        Assert-That ($stdout.Wait(2000) -and $stderr.Wait(2000)) 'Controlled child left output pipes open.'
        $output=$stdout.Result+"`n"+$stderr.Result;$code=$process.ExitCode
        [IO.File]::WriteAllText([IO.Path]::ChangeExtension($parameterFile,'.output.txt'),$output,[Text.UTF8Encoding]::new($false))
        Assert-That (!(Test-Path -LiteralPath (Join-Path $toolsRoot 'sdk-tripwire.txt'))) 'Production attempted an unapproved native Android SDK tool.'
        Assert-That ($output -notmatch 'No controlled response for ADB argv|The fixture rejects|Unknown protected setting|Mutation of unknown fixture|Explicit isolated EVE|Uncontrolled EVE|Unknown EVE console fixture|Exactly one owned preview|unexpected ADB delegate') ('Controlled harness failure, not an expected rejection: '+$output)
        return [pscustomobject]@{Code=$code;Output=$output;Seconds=$timer.Elapsed.TotalSeconds}
    }finally{$timer.Stop();$script:childTimes+=$timer.Elapsed.TotalSeconds;$process.Dispose()}
}
function Wizard-Parameters([hashtable]$Extra=@{}){
    $parameters=@{AdbPath=$adbPath;KitRoot=$script:kitRoot}
    foreach($key in $Extra.Keys){$parameters[$key]=$Extra[$key]};return $parameters
}
function Invoke-Wizard([object[]]$Responses=@(),[hashtable]$Extra=@{}){Invoke-Controlled (Wizard-Parameters $Extra) $Responses}
function Pinned-Parameters([hashtable]$Extra=@{}){
    $parameters=@{Mode='Plan';Serial=$selectedSerial;ExpectedModel=$profile.match.model;ExpectedFingerprint=$profile.match.fingerprint;
        AdbPath=$adbPath;AdbServerPort=5037;CommandTimeoutSeconds=10;InstallTimeoutSeconds=10;PinnedRelease='candidate.15';
        Apk=$script:kitApk;PlanPath=$script:backendPlan;ApkSignerPath=(Join-Path $toolsRoot 'no-sdk-signer');AaptPath=(Join-Path $toolsRoot 'no-sdk-aapt')}
    foreach($key in $Extra.Keys){$parameters[$key]=$Extra[$key]};return $parameters
}
function Assert-NoMutation($Result,[switch]$Refused){
    if($Refused){Assert-That ($Result.Code -ne 0) ('Unsafe operation succeeded: '+$Result.Output)}
    Assert-That (@(Get-Mutations).Count -eq 0) ('Unexpected controlled device mutation: '+$Result.Output)
}
function Assert-Protected($Before){
    $state=Get-Fixture
    Assert-That (($state.Settings|ConvertTo-Json -Compress) -ceq ($Before.Settings|ConvertTo-Json -Compress)) 'Remote services or sleep settings changed.'
    Assert-That ($state.Packages.PSObject.Properties['com.example.unrelated'].Value.State -eq $Before.Packages.PSObject.Properties['com.example.unrelated'].Value.State) 'An unrelated package changed.'
}
function Initialize-Case{
    $script:caseNumber++;$script:caseDir=Join-Path $testRoot ('case-'+$script:caseNumber);[void][IO.Directory]::CreateDirectory($script:caseDir)
    $script:kitRoot=Join-Path $script:caseDir 'extracted kit';[void][IO.Directory]::CreateDirectory($script:kitRoot)
    $script:kitApk=Join-Path $script:kitRoot $apkName;[IO.File]::Copy($sourceApk,$script:kitApk)
    $script:statePath=Join-Path $script:caseDir 'state.json';$env:SOFTLUMI_TV_SETUP_TEST_STATE=$script:statePath
    $script:backendPlan=Join-Path $script:caseDir 'pinned-plan.json'
    $packages=[ordered]@{}
    foreach($package in @($stock)+$optional+@($soft,'com.example.unrelated')){
        $packages[$package]=[ordered]@{Installed=$true;State=0;ComponentState=0;VersionCode=34;Hash=('d'*64)}
    }
    Write-Json $script:statePath ([ordered]@{Serial=$selectedSerial;Ready='device';Qemu='0';Model=$profile.match.model;
        Fingerprint=$profile.match.fingerprint;Sdk='34';Abi='armeabi-v7a';UserId='0';CandidatePackage=$soft;CandidateVersion=36;
        CandidateHash=$expectedApkHash;Cert=('a'*64);SignerExit=0;AaptExit=0;ExtraSigner=$false;PostInstallHashMismatch=$false;
        LaunchableActivity=($soft+'.MainActivity');WrongForeground=$false;AutoHomeAfterStockDisable='';Home=$stockComponent;Foreground=$stockComponent;
        Packages=$packages;Faults=@();Devices=@([pscustomobject]@{serial=$selectedSerial;state='device'});
        Settings=[ordered]@{'secure/enabled_accessibility_services'='com.example.remote/com.example.remote.KeyService';
            'secure/accessibility_enabled'='1';'secure/sleep_timeout'='10800000';'system/screen_off_timeout'='10800000'}})
}
function Test-Case([string]$Name,[scriptblock]$Body){
    if($Case.Count -gt 0 -and $Name -notin $Case){return}
    Initialize-Case;$timer=[Diagnostics.Stopwatch]::StartNew()
    try{& $Body;$script:results+=[pscustomobject]@{Name=$Name;Passed=$true;Seconds=$timer.Elapsed.TotalSeconds};Write-Output ('PASS '+$Name)}
    catch{$script:results+=[pscustomobject]@{Name=$Name;Passed=$false;Seconds=$timer.Elapsed.TotalSeconds;Error=$_.Exception.Message};Write-Output ('FAIL '+$Name+': '+$_.Exception.Message)}
    finally{$timer.Stop()}
}

try{
    Test-Case 'pinned-plan-uses-real-apk-without-sdk' {
        $before=Get-Content -LiteralPath $statePath -Raw
        $result=Invoke-Controlled (Pinned-Parameters) -Backend;Assert-That ($result.Code -eq 0) $result.Output
        $plan=Read-Json $backendPlan
        Assert-That ($plan.schemaVersion -eq 2 -and $plan.verification.kind -ceq 'PinnedRelease' -and $plan.verification.releaseId -ceq 'candidate.15') 'Pinned verifier was not persisted.'
        Assert-That (@($plan.tools.PSObject.Properties).Count -eq 2 -and $null -eq $plan.tools.PSObject.Properties['apkSignerPath'] -and $null -eq $plan.tools.PSObject.Properties['aaptPath']) 'Pinned plan unexpectedly depends on SDK tools.'
        Assert-That ($plan.apk.sha256 -ceq $expectedApkHash -and $plan.apk.versionCode -ceq '36') 'Pinned release did not bind the supplied APK36.'
        Assert-NoMutation $result;Assert-That ((Get-Content -LiteralPath $statePath -Raw) -ceq $before) 'Planning changed the fixture.'
    }
    Test-Case 'pinned-corrupt-apk-refused-before-adb' {
        $file=[IO.File]::Open($kitApk,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite)
        try{$value=$file.ReadByte();$file.Position=0;$file.WriteByte([byte]($value -bxor 1))}finally{$file.Dispose()}
        $result=Invoke-Controlled (Pinned-Parameters) -Backend;Assert-NoMutation $result -Refused
        Assert-That ($result.Output -match 'pinned_apk_sha256_mismatch') 'Wrong APK was refused for an unrelated reason.'
        Assert-That (@(Get-Calls).Count -eq 0) 'Corrupt pinned APK reached ADB.'
    }
    if($legacyApkSource){
        Test-Case 'legacy-pinned-release-remains-inspectable-and-restorable' {
            $before=Get-Fixture
            $legacyApk=Join-Path $kitRoot 'softlumi-0.3.0-candidate.14.apk'
            [IO.File]::Copy($legacyApkSource,$legacyApk)
            Set-Fixture @{CandidateVersion=35;CandidateHash=$legacyApkHash}
            $parameters=Pinned-Parameters @{PinnedRelease='candidate.14';Apk=$legacyApk;ProfileId='mi-box-s3-android14';SetHome=$true;DisableStockHome=$true;DisablePackages=@($optional[0])}
            $result=Invoke-Controlled $parameters -Backend;Assert-That ($result.Code -eq 0) $result.Output
            $plan=Read-Json $backendPlan
            Assert-That ($plan.verification.releaseId -ceq 'candidate.14' -and $plan.apk.sha256 -ceq $legacyApkHash -and $plan.apk.versionCode -ceq '35') 'Legacy pinned record changed.'
            $journalPath=Join-Path $caseDir 'legacy-journal.json'
            $parameters.Mode='Apply';$parameters.ApprovedPlanSha256=Digest $backendPlan;$parameters.JournalPath=$journalPath
            $result=Invoke-Controlled $parameters -Backend;Assert-That ($result.Code -eq 0) $result.Output
            Assert-That (@(Get-Installs).Count -eq 1 -and (Get-Fixture).Home -ceq $softComponent) 'Legacy pinned Apply did not preserve its exact transaction.'
            [IO.File]::Move($kitApk,($kitApk+'.not-needed-for-legacy-restore'))
            [IO.File]::Move($legacyApk,($legacyApk+'.not-needed-for-legacy-restore'))
            Clear-Calls;$rawJournal=Get-Content -LiteralPath $journalPath -Raw
            $result=Invoke-Wizard @() @{Mode='Inspect';JournalPath=$journalPath};Assert-That ($result.Code -eq 0) $result.Output;Assert-NoMutation $result
            Assert-That ((Get-Content -LiteralPath $journalPath -Raw) -ceq $rawJournal) 'Legacy inspection rewrote the saved journal.'
            $result=Invoke-Wizard @('RESTORE') @{Mode='Restore';JournalPath=$journalPath};Assert-That ($result.Code -eq 0) $result.Output
            $state=Get-Fixture
            Assert-That ($state.Home -ceq $stockComponent -and $state.Packages.PSObject.Properties[$stock].Value.State -eq 0 -and $state.Packages.PSObject.Properties[$optional[0]].Value.State -eq 0) 'Legacy Restore did not return the saved HOME and package states.'
            Assert-That ($state.Packages.PSObject.Properties[$soft].Value.VersionCode -eq 35 -and $state.Packages.PSObject.Properties[$soft].Value.Hash -ceq $legacyApkHash -and @(Get-Installs).Count -eq 0) 'Legacy Restore needed an APK or changed the installed version.'
            Assert-Protected $before
        }
    }
    Test-Case 'pinned-exact-profile-refuses-other-firmware' {
        $fingerprint='Example/vendor/device:14/test/other:user/release-keys';Set-Fixture @{Fingerprint=$fingerprint}
        $result=Invoke-Controlled (Pinned-Parameters @{ExpectedFingerprint=$fingerprint;ProfileId='mi-box-s3-android14';SetHome=$true;DisableStockHome=$true}) -Backend
        Assert-NoMutation $result -Refused;Assert-That ($result.Output -match 'profile_firmware_mismatch') 'Wrong firmware failed for an unrelated reason.'
    }
    Test-Case 'pinned-plan-record-cannot-be-replaced-by-json' {
        $result=Invoke-Controlled (Pinned-Parameters) -Backend;Assert-That ($result.Code -eq 0) $result.Output
        $plan=Read-Json $backendPlan;$plan.verification.apkSha256='f'*64;Write-Json $backendPlan $plan;Clear-Calls
        $parameters=Pinned-Parameters @{Mode='Apply';PlanPath=$backendPlan;ApprovedPlanSha256=(Digest $backendPlan);JournalPath=(Join-Path $caseDir 'journal.json')}
        $result=Invoke-Controlled $parameters -Backend;Assert-NoMutation $result -Refused
        Assert-That ($result.Output -match 'pinned_verification_mismatch') 'Edited verifier record was refused for an unrelated reason.'
    }
    Test-Case 'wizard-preview-never-applies-defaults' {
        $before=Get-Content -LiteralPath $statePath -Raw
        $result=Invoke-Wizard @('','') @{PreviewOnly=$true};Assert-That ($result.Code -eq 0) $result.Output;Assert-NoMutation $result
        Assert-That ($result.Output.Contains('APK: Eve Box 0.3.0-candidate.15 (code 36).') -and !$result.Output.Contains('APK: softlumi') -and !$result.Output.Contains('пока остаётся softlumi')) 'Preview did not show the current Eve Box release name and version.'
        $plans=@(Get-Plans);Assert-That ($plans.Count -eq 1 -and @(Get-Journals).Count -eq 0) 'Preview failed to create exactly one plan without a journal.'
        $plan=Read-Json $plans[0].FullName
        Assert-That (!$plan.selection.setHome -and !$plan.selection.disableStockHome -and @($plan.selection.disablePackages).Count -eq 0) 'Blank answers selected an optional mutation.'
        Assert-That (@(Answers).Count -eq 2) 'Preview asked for Apply approval or consumed an unexpected response.'
        Assert-That ((Get-Content -LiteralPath $statePath -Raw) -ceq $before) 'Preview changed device state.'
    }
    Test-Case 'wizard-final-cancel-keeps-reviewed-plan-read-only' {
        $result=Invoke-Wizard @('yes','yes','1','no');Assert-That ($result.Code -eq 0) $result.Output;Assert-NoMutation $result
        $plans=@(Get-Plans);Assert-That ($plans.Count -eq 1 -and @(Get-Journals).Count -eq 0) 'Cancellation created a mutation journal or lost its reviewed plan.'
        $plan=Read-Json $plans[0].FullName
        Assert-That ($plan.selection.setHome -and $plan.selection.disableStockHome -and @($plan.selection.disablePackages).Count -eq 1) 'The cancelled plan did not contain the explicit choices.'
    }
    Test-Case 'wizard-eof-is-never-approval' {
        $result=Invoke-Wizard @('','');Assert-That ($result.Code -eq 0) $result.Output;Assert-NoMutation $result
        Assert-That (@(Answers|Where-Object eof).Count -eq 1 -and @(Get-Journals).Count -eq 0) 'EOF did not stop at the approval boundary.'
    }
    Test-Case 'wizard-multiple-devices-cancel-does-not-choose' {
        Set-Fixture @{Devices=@([pscustomobject]@{serial=$otherSerial;state='device'},[pscustomobject]@{serial=$selectedSerial;state='device'})}
        $result=Invoke-Wizard @('');Assert-That ($result.Code -eq 0) $result.Output;Assert-NoMutation $result
        Assert-That (@(Get-Calls|Where-Object{$_.Args -contains '-s'}).Count -eq 0 -and @(Get-Plans).Count -eq 0) 'Cancellation inspected or selected a target implicitly.'
    }
    Test-Case 'wizard-explicit-missing-target-refused' {
        $result=Invoke-Wizard @() @{Serial='unlisted-controlled-tv'};Assert-NoMutation $result -Refused
        Assert-That (@(Get-Calls|Where-Object{$_.Args -contains '-s'}).Count -eq 0 -and @(Get-Plans).Count -eq 0) 'Missing explicit target fell back to the available device.'
    }
    Test-Case 'wizard-unauthorized-target-never-installs' {
        Set-Fixture @{Devices=@([pscustomobject]@{serial=$selectedSerial;state='unauthorized'})}
        $result=Invoke-Wizard;Assert-NoMutation $result -Refused;Assert-That (@(Get-Plans).Count -eq 0) 'Unauthorized target produced an applyable plan.'
    }
    Test-Case 'wizard-connect-only-to-explicit-address' {
        # Parallel PS5/PS7 runs must not share the frontend's per-target mutex.
        $connectedAddress=if($PSVersionTable.PSVersion.Major -le 5){'198.51.100.27'}else{'198.51.100.28'}
        $connectedSerial=$connectedAddress+':5555';Set-Fixture @{Devices=@();Serial=$connectedSerial}
        $result=Invoke-Wizard @('');Assert-That ($result.Code -eq 0) $result.Output;Assert-NoMutation $result
        Assert-That (@(Get-Calls|Where-Object{$_.Args -contains 'connect'}).Count -eq 0) 'Blank address initiated a connection.'
        Clear-Calls
        $result=Invoke-Wizard @($connectedAddress,'','') @{PreviewOnly=$true};Assert-That ($result.Code -eq 0) $result.Output;Assert-NoMutation $result
        $connections=@(Get-Calls|Where-Object{$_.Args -contains 'connect'})
        Assert-That ($connections.Count -eq 1 -and $connections[0].Args[3] -ceq $connectedSerial) 'Connect did not use only the explicitly entered address and default port.'
        $plan=Read-Json (@(Get-Plans)[0].FullName)
        Assert-That ($plan.target.serial -ceq $connectedSerial) 'Connected preview planned for another target.'
    }
    Test-Case 'wizard-all-selects-only-available-optional-apps' {
        $state=Get-Fixture;$state.Packages.PSObject.Properties[$optional[-1]].Value.Installed=$false;Write-Json $statePath $state
        $result=Invoke-Wizard @('','all') @{PreviewOnly=$true};Assert-That ($result.Code -eq 0) $result.Output;Assert-NoMutation $result
        $plan=Read-Json (@(Get-Plans)[0].FullName)
        Assert-That (!$plan.selection.setHome -and !$plan.selection.disableStockHome) 'All optional apps selected a HOME action.'
        Assert-That (@($plan.selection.disablePackages).Count -eq ($optional.Count-1) -and @($plan.selection.disablePackages) -cnotcontains $stock -and @($plan.selection.disablePackages) -cnotcontains $optional[-1]) 'All selected stock, missing or arbitrary packages.'
    }
    Test-Case 'wizard-unmatched-firmware-installs-only' {
        $before=Get-Fixture;Set-Fixture @{Fingerprint='Example/vendor/device:14/test/other:user/release-keys'}
        $result=Invoke-Wizard @('YES');Assert-That ($result.Code -eq 0) $result.Output
        $plan=Read-Json (@(Get-Plans)[0].FullName)
        Assert-That ($plan.profile.id -ceq 'generic-install-only' -and !$plan.selection.setHome -and !$plan.selection.disableStockHome -and @($plan.selection.disablePackages).Count -eq 0) 'Unknown firmware acquired a device-specific profile.'
        Assert-That (@(Get-Installs).Count -eq 1 -and @(Get-PackageMutations).Count -eq 0 -and (Get-Fixture).Home -ceq $stockComponent) 'Installation-only changed packages or HOME.'
        Assert-Protected $before
    }
    Test-Case 'wizard-exact-preview-hash-survives-console-confirmation' {
        $result=Invoke-Wizard @('','',@{action='TamperPlan';answer='YES'});Assert-NoMutation $result -Refused
        Assert-That ($result.Output -match 'approved_plan_hash_mismatch|План изменился после просмотра') ('The changed displayed plan did not fail at the approved hash boundary: '+$result.Output)
        $events=@(Answers|Where-Object action -eq 'TamperPlan');Assert-That ($events.Count -eq 1 -and $events[0].beforeSha256 -cne $events[0].afterSha256) 'Plan tamper did not occur after the preview.'
        Assert-That (@(Get-Journals).Count -eq 0) 'Tampered plan created a mutation journal.'
    }
    Test-Case 'wizard-target-drift-after-preview-never-applies' {
        $result=Invoke-Wizard @('','',@{action='ChangeFingerprint';answer='YES'});Assert-NoMutation $result -Refused
        Assert-That ($result.Output -match 'target_identity_mismatch|target_changed_since_preview') 'Changed physical identity was refused for an unrelated reason.'
    }
    Test-Case 'wizard-multiple-choice-apply-inspect-cancel-restore-roundtrip' {
        $before=Get-Fixture
        Set-Fixture @{Devices=@([pscustomobject]@{serial=$otherSerial;state='device'},[pscustomobject]@{serial=$selectedSerial;state='device'})}
        $result=Invoke-Wizard @('2','yes','yes','1','YES');Assert-That ($result.Code -eq 0) $result.Output
        $journals=@(Get-Journals);Assert-That ($journals.Count -eq 1) 'Successful Apply did not retain its private journal.'
        $journalPath=$journals[0].FullName;$journal=Read-Json $journalPath
        Assert-That ($journal.status -ceq 'Applied' -and $journal.plan.target.serial -ceq $selectedSerial -and $journal.planSha256 -ceq (Digest (@(Get-Plans)[0].FullName))) 'Apply did not use the exact selected target and preview plan.'
        Assert-That (@(Get-Installs).Count -eq 1 -and (Get-Fixture).Home -ceq $softComponent) 'Requested HOME transition failed.'
        Assert-That ((Get-Fixture).Packages.PSObject.Properties[$optional[0]].Value.State -eq 3 -and (Get-Fixture).Packages.PSObject.Properties[$stock].Value.State -eq 3) 'Explicit app/HOME choices were not applied.'
        Assert-That (@(Get-Mutations|Where-Object{$_.Args[3] -cne $selectedSerial}).Count -eq 0) 'Wizard mutated an unselected device.'
        Assert-Protected $before
        [IO.File]::Move($kitApk,($kitApk+'.not-needed-for-restore'));Clear-Calls
        $rawJournal=Get-Content -LiteralPath $journalPath -Raw
        $result=Invoke-Wizard @() @{Mode='Inspect';JournalPath=$journalPath};Assert-That ($result.Code -eq 0) $result.Output;Assert-NoMutation $result
        Assert-That ((Get-Content -LiteralPath $journalPath -Raw) -ceq $rawJournal) 'Inspect changed the journal.'
        $result=Invoke-Wizard @('no') @{Mode='Restore';JournalPath=$journalPath};Assert-That ($result.Code -eq 0) $result.Output;Assert-NoMutation $result
        Assert-That ((Get-Fixture).Home -ceq $softComponent) 'Declined Restore changed HOME.'
        $result=Invoke-Wizard @('RESTORE') @{Mode='Restore';JournalPath=$journalPath};Assert-That ($result.Code -eq 0) $result.Output
        $state=Get-Fixture
        Assert-That ($state.Home -ceq $stockComponent -and $state.Packages.PSObject.Properties[$stock].Value.State -eq 0 -and $state.Packages.PSObject.Properties[$optional[0]].Value.State -eq 0) 'Restore did not return the recorded configuration.'
        Assert-That ($state.Packages.PSObject.Properties[$soft].Value.VersionCode -eq 36 -and $state.Packages.PSObject.Properties[$soft].Value.Hash -ceq $expectedApkHash -and @(Get-Installs).Count -eq 0) 'Restore needed an APK, reinstalled it or changed installed bytes.'
        Assert-Protected $before
    }
    Test-Case 'wizard-pending-install-blocks-retry-and-restore' {
        Set-Fixture @{Faults=@([pscustomobject]@{Match='^install -r --user 0 ';Mode='AfterCommitExit';Remaining=1;ExitCode=37;Seconds=1})}
        $result=Invoke-Wizard @('yes','yes','1','YES');Assert-That ($result.Code -ne 0) 'Unknown install result was accepted.'
        $journals=@(Get-Journals);Assert-That ($journals.Count -eq 1 -and @(Get-Installs).Count -eq 1) 'Unknown installation did not preserve exactly one attempt and journal.'
        $journalPath=$journals[0].FullName;$journal=Read-Json $journalPath
        Assert-That ($journal.status -ceq 'NeedsReconcile' -and $journal.actions[0].status -ceq 'NeedsReconcile') 'Unknown commit became retryable.'
        Assert-That (@(Get-PackageMutations).Count -eq 0 -and (Get-Fixture).Home -ceq $stockComponent) 'Unknown install continued into profile changes.'
        Clear-Calls;$rawJournal=Get-Content -LiteralPath $journalPath -Raw
        $result=Invoke-Wizard @('yes','yes','1','YES');Assert-NoMutation $result -Refused
        Assert-That (@(Get-Journals).Count -eq 1 -and @(Get-Plans).Count -eq 1) 'Second Install created a new transaction around a pending journal.'
        [IO.File]::Move($kitApk,($kitApk+'.not-needed-for-inspect'));Clear-Calls
        $result=Invoke-Wizard @() @{Mode='Inspect';JournalPath=$journalPath};Assert-That ($result.Code -eq 0) $result.Output;Assert-NoMutation $result
        $result=Invoke-Wizard @('RESTORE') @{Mode='Restore';JournalPath=$journalPath};Assert-NoMutation $result -Refused
        Assert-That ((Get-Content -LiteralPath $journalPath -Raw) -ceq $rawJournal) 'Inspection/refused Restore rewrote terminal uncertainty.'
    }
    Test-Case 'wizard-restore-journal-drift-never-mutates' {
        $result=Invoke-Wizard @('yes','yes','1','YES');Assert-That ($result.Code -eq 0) $result.Output
        $journalPath=@(Get-Journals)[0].FullName;Clear-Calls
        $result=Invoke-Wizard @(@{action='TamperJournal';answer='RESTORE'}) @{Mode='Restore';JournalPath=$journalPath}
        Assert-NoMutation $result -Refused
        Assert-That ($result.Output -match 'journal_changed_since_preview|Журнал изменился после просмотра') ('Changed Restore journal did not fail at the confirmation boundary: '+$result.Output)
        $events=@(Answers|Where-Object action -eq 'TamperJournal')
        Assert-That ($events.Count -eq 1 -and $events[0].beforeSha256 -cne $events[0].afterSha256) 'Journal tamper did not occur after Inspect.'
        Assert-That ((Get-Fixture).Home -ceq $softComponent -and (Get-Fixture).Packages.PSObject.Properties[$stock].Value.State -eq 3) 'A changed journal altered the active HOME configuration.'
    }
    Test-Case 'wizard-pending-created-at-confirmation-stops-before-apply' {
        # Same-version installs leave the before-snapshot unchanged. Without
        # the post-confirmation pending check, the waiting plan could retry.
        $state=Get-Fixture;$state.Packages.PSObject.Properties[$soft].Value.VersionCode=36
        $state.Packages.PSObject.Properties[$soft].Value.Hash=$expectedApkHash
        $state.Faults=@([pscustomobject]@{Match='^install -r --user 0 ';Mode='AfterCommitExit';Remaining=1;ExitCode=37;Seconds=1})
        Write-Json $statePath $state
        $result=Invoke-Wizard @('','',@{action='CreatePendingAtConfirmation';answer='YES'})
        Assert-NoMutation $result -Refused
        $events=@(Answers|Where-Object action -eq 'CreatePendingAtConfirmation')
        Assert-That ($events.Count -eq 1 -and $events[0].externalOutcomeUnknown -and $events[0].externalInstallCount -eq 1) 'The external actor did not perform exactly one unknown same-version install.'
        Assert-That (@(Get-Plans).Count -eq 2 -and @(Get-Journals).Count -eq 1) 'Waiting wizard created a second mutation journal.'
        $journal=Read-Json (@(Get-Journals)[0].FullName)
        Assert-That ($journal.status -ceq 'NeedsReconcile' -and (Get-Fixture).Home -ceq $stockComponent) 'Pending transaction was adopted or changed HOME.'
    }
    Test-Case 'wizard-target-lock-refuses-second-and-releases-on-cancel' {
        $ready=Join-Path $caseDir 'wizard-lock-ready.marker';$release=Join-Path $caseDir 'wizard-lock-release.marker'
        $script:secondWizardResult=$null;$script:gateFailure=''
        $duringFirst={
            try{
                $deadline=[DateTime]::UtcNow.AddSeconds(25)
                while(![IO.File]::Exists($ready) -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 25}
                if(![IO.File]::Exists($ready)){throw 'First wizard did not reach its controlled confirmation prompt.'}
                $script:secondWizardResult=Invoke-Wizard @('','') @{PreviewOnly=$true}
            }catch{$script:gateFailure=$_.Exception.Message}
            finally{[IO.File]::WriteAllText($release,'release-owned-wizard')}
        }
        $first=Invoke-Controlled (Wizard-Parameters) @('','',@{action='HoldConfirmation';answer='no'}) -WhileRunning $duringFirst
        Assert-That (!$script:gateFailure) $script:gateFailure
        Assert-That ($first.Code -eq 0) $first.Output
        Assert-That ($null -ne $script:secondWizardResult -and $script:secondWizardResult.Code -ne 0) 'A second actual wizard entered the first target transaction.'
        Assert-NoMutation $first
        Assert-That (@(Get-Plans).Count -eq 1 -and @(Get-Journals).Count -eq 0) 'Contending wizard created another plan or mutation journal.'
        $third=Invoke-Wizard @('','') @{PreviewOnly=$true};Assert-That ($third.Code -eq 0) $third.Output;Assert-NoMutation $third
        Assert-That (@(Get-Plans).Count -eq 2) 'Cancelled wizard did not release its target lock for the next preview.'
    }
    Assert-That ($results.Count -gt 0) 'No case matched the requested filter.'
    $failed=@($results|Where-Object{!$_.Passed}).Count
    $hashes=[ordered]@{}
    foreach($relative in @('setup-eve-box.ps1','EveSetup.Wizard.psm1','setup-tv.ps1','TvSetup.Common.psm1','test-eve-setup.ps1','tests/eve-setup-driver.ps1','tests/eve-setup-adb-stub.ps1','tests/tv-setup-tool-stub.ps1')){$hashes[$relative]=Digest (Join-Path $PSScriptRoot $relative)}
    $sourceEndSha256=Source-Hashes
    $sourcesUnchanged=($sourceStartSha256|ConvertTo-Json -Compress) -ceq ($sourceEndSha256|ConvertTo-Json -Compress)
    $summary=[ordered]@{SchemaVersion=1;PowerShellVersion=$PSVersionTable.PSVersion.ToString();OfflineOnly=$true;RealApkSha256=$expectedApkHash;
        LegacyApkSha256=$(if($legacyApkSource){$legacyApkHash}else{''});
        ChildDeadlineSeconds=70;ChildCalls=$childTimes.Count;MaximumChildSeconds=($childTimes|Measure-Object -Maximum).Maximum;SourceSha256=$hashes;
        SourceStartSha256=$sourceStartSha256;SourceEndSha256=$sourceEndSha256;SourcesUnchanged=$sourcesUnchanged;
        CaseCount=$results.Count;Passed=@($results|Where-Object Passed).Count;Failed=$failed;Results=$results}
    if($ReportPath){$report=[IO.Path]::GetFullPath($ReportPath);[void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($report));Write-Json $report $summary}
    Write-Output ('EVE offline setup: '+$summary.Passed+' passed, '+$failed+' failed. No real Android executable or device was used.')
    if($KeepFixtures){Write-Output ('Controlled fixtures retained: '+$testRoot)}
    if($failed -gt 0 -or !$sourcesUnchanged){exit 1};exit 0
}finally{
    foreach($name in $environmentNames){[Environment]::SetEnvironmentVariable($name,$previousEnvironment[$name],'Process')}
    if(!$KeepFixtures){
        $resolved=[IO.Path]::GetFullPath($testRoot);$prefix=[IO.Path]::GetFullPath($PSScriptRoot)+[IO.Path]::DirectorySeparatorChar
        if(!$resolved.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase) -or
            ![IO.Directory]::Exists($resolved) -or ((Get-Item -LiteralPath $resolved).Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            ![IO.File]::Exists((Join-Path $resolved '.owned-fixture')) -or [IO.File]::ReadAllText((Join-Path $resolved '.owned-fixture')) -cne $marker){throw 'Refusing cleanup outside the verified owned EVE fixture.'}
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
