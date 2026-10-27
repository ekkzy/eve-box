#requires -Version 5.1
<#
.SYNOPSIS
Validates and installs one explicitly supplied softlumi APK; HOME is opt-in.
.DESCRIPTION
Stage A accepts only a named, verified emulator. Physical targets require the
explicit StageBPhysicalDevice switch and independently selected identity.
DryRun validates local APK bytes and runs no ADB commands.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$ApkSha256,
    [Parameter(Mandatory = $true)][string]$SigningCertSha256,
    [string]$EmulatorSerial,
    [switch]$StageBPhysicalDevice,
    [string]$DeviceSerial,
    [string]$ExpectedModel,
    [string]$ExpectedBuildFingerprint,
    [string]$JournalPath = (Join-Path (Get-Location) 'softlumi-install-journal.json'),
    [string]$AdbPath = 'adb',
    [string]$ApkSignerPath = 'apksigner',
    [string]$AaptPath = 'aapt',
    [switch]$SetHome,
    [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Installer.Common.psm1') -Force -DisableNameChecking
$script:Package = 'io.github.ekkzy.softlumi'
$script:Component = 'io.github.ekkzy.softlumi/io.github.ekkzy.softlumi.MainActivity'
$verified = $null
$journal = $null
try {
    $request = Get-TargetRequest $EmulatorSerial $StageBPhysicalDevice.IsPresent $DeviceSerial $ExpectedModel $ExpectedBuildFingerprint
    $journalFile = [IO.Path]::GetFullPath($JournalPath)
    if (Test-Path -LiteralPath $journalFile) { throw 'JournalPath already exists. Retain it for rollback and use a new path for this transaction.' }
    $signer = Resolve-InstallerTool $ApkSignerPath
    $aapt = Resolve-InstallerTool $AaptPath
    $verified = Get-ValidatedApk $Apk $ApkSha256 $SigningCertSha256 $signer $aapt
    Write-Output "Verified $script:Package versionCode=$($verified.VersionCode); SHA-256=$($verified.Sha256); certificate=$($verified.CertSha256)"
    if ($DryRun) {
        Write-Output "DRY RUN: no ADB process will be started; no journal will be written. Target: $($request.Kind) $($request.Serial)."
        Write-Output "Would run: adb -s $($request.Serial) get-state"
        Write-Output "Would run: adb -s $($request.Serial) shell getprop ro.kernel.qemu; then confirm model, fingerprint, and primary user."
        Write-Output 'Would capture current package state and write the transaction journal before install.'
        Write-Output "Would run: adb -s $($request.Serial) install -r --user 0 <verified-local-apk-copy>"
        Write-Output 'Would verify the installed version and preserve app data.'
        if ($SetHome) {
            Write-Output 'Would capture the previous HOME before installation and retain it in the journal.'
            Write-Output "Would enable only $script:Component if currently disabled, otherwise preserve its override; then set and verify HOME."
        } else { Write-Output 'HOME remains unchanged (SetHome was not requested).' }
        exit 0
    }
    $adb = Resolve-InstallerTool $AdbPath
    $target = Confirm-Target $adb $request
    $before = Get-PackageSnapshot $adb $target.Serial
    if ($before.Installed -and [long]$before.VersionCode -gt [long]$verified.VersionCode) { throw 'Installed versionCode is newer. Downgrades are refused; app data is preserved.' }
    $previousHome = $null
    if ($SetHome) { $previousHome = Get-CurrentHome $adb $target.Serial }
    $journal = [pscustomobject][ordered]@{
        SchemaVersion = 1; Tool = 'softlumi-installer'; TransactionId = [guid]::NewGuid().ToString('N')
        CreatedUtc = [DateTime]::UtcNow.ToString('o'); UpdatedUtc = ''; Status = 'Prepared'
        Target = $target; Package = $script:Package; Component = $script:Component
        ApkSha256 = $verified.Sha256; SigningCertSha256 = $verified.CertSha256; VersionCode = $verified.VersionCode
        HomeRequested = $SetHome.IsPresent
        Previous = [pscustomobject]@{ HomeComponent = $previousHome; Installed = $before.Installed; ComponentState = $before.ComponentState; VersionCode = $before.VersionCode }
    }
    Write-InstallJournal $journalFile $journal -Create
    $journal.Status = 'Installing'; Write-InstallJournal $journalFile $journal
    try {
        # Install exactly the validated local copy. Never uninstall, clear data, or pass -d.
        $installOutput = Invoke-TargetAdb $adb $target.Serial @('install', '-r', '--user', '0', $verified.File)
        if ($installOutput -notmatch '(?m)^Success\s*$') { throw "ADB did not report install success: $installOutput" }
        $after = Get-PackageSnapshot $adb $target.Serial
        if (!$after.Installed -or $after.VersionCode -cne $verified.VersionCode) { throw 'Installed package/version did not match the verified APK.' }
    } catch {
        $journal.Status = 'InstallFailed'; Write-InstallJournal $journalFile $journal
        throw
    }
    $journal.Status = 'Installed'; Write-InstallJournal $journalFile $journal
    if ($SetHome) {
        $journal.Status = 'ChangingHome'; Write-InstallJournal $journalFile $journal
        try {
            $homeState = Get-PackageSnapshot $adb $target.Serial
            if (!$homeState.Installed -or $homeState.VersionCode -cne $verified.VersionCode) { throw 'Installed package/version changed before HOME selection.' }
            if ($homeState.ComponentState -eq 2) {
                $enabled = Invoke-TargetAdb $adb $target.Serial @('shell', 'pm', 'enable', '--user', '0', $script:Component)
                if ($enabled -notmatch 'new state:\s*enabled\s*$') { throw "Component enable was not confirmed: $enabled" }
            } else { Write-Output "MainActivity is already enabled; preserving override=$($homeState.ComponentState)." }
            $homeOutput = Invoke-TargetAdb $adb $target.Serial @('shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $script:Component)
            if ($homeOutput -notmatch '(?i)success') { throw "HOME change was not confirmed: $homeOutput" }
            $actualHome = Get-CurrentHome $adb $target.Serial
            if ($actualHome -cne $script:Component -and $actualHome -cne ($script:Package + '/.MainActivity')) { throw 'HOME resolution did not return softlumi.' }
            $journal.Status = 'HomeChanged'; Write-InstallJournal $journalFile $journal
        } catch {
            $journal.Status = 'HomeChangeFailed'; Write-InstallJournal $journalFile $journal
            throw
        }
    }
    Write-Output "Installed successfully. HOME requested: $($SetHome.IsPresent). Keep the private journal: $journalFile"
    exit 0
} catch {
    [Console]::Error.WriteLine('softlumi install: ' + $_.Exception.Message)
    if ($null -ne $journal) { Write-Output "Transaction journal retained: $journalFile (status $($journal.Status))." }
    exit 1
} finally { Remove-ValidatedApk $verified }
