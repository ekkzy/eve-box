#requires -Version 5.1
<#
.SYNOPSIS
Restores the journaled HOME and exact Eve Box component override; keeps APK/data.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$JournalPath,
    [string]$EmulatorSerial,
    [switch]$StageBPhysicalDevice,
    [string]$DeviceSerial,
    [string]$ExpectedModel,
    [string]$ExpectedBuildFingerprint,
    [string]$AdbPath = 'adb',
    [switch]$DryRun
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'Installer.Common.psm1') -Force -DisableNameChecking
$script:Package = 'io.github.ekkzy.evebox'
$script:Component = 'io.github.ekkzy.evebox/io.github.ekkzy.evebox.MainActivity'
$journal = $null
$mutating = $false
try {
    $request = Get-TargetRequest $EmulatorSerial $StageBPhysicalDevice.IsPresent $DeviceSerial $ExpectedModel $ExpectedBuildFingerprint
    $journalFile = [IO.Path]::GetFullPath($JournalPath)
    $journal = Read-InstallJournal $journalFile $request
    if ($DryRun) {
        Write-Output "DRY RUN: no ADB process will be started; journal unchanged. Target: $($request.Kind) $($request.Serial)."
        Write-Output 'Would verify target identity and the installed package version against the journal.'
        if ($journal.HomeRequested) {
            Write-Output "Would restore HOME $($journal.Previous.HomeComponent), verify it, then restore $($journal.Component) override=$($journal.Previous.ComponentState) only if it differs."
        } else { Write-Output 'No HOME/component changes were requested by this transaction; there is no device mutation to undo.' }
        Write-Output 'The installed APK and all app data are retained. Rollback does not downgrade or uninstall.'
        exit 0
    }
    $adb = Resolve-InstallerTool $AdbPath
    $target = Confirm-Target $adb $request
    Assert-JournalIdentity $journal $target
    $current = Get-PackageSnapshot $adb $target.Serial
    if (!$current.Installed -or $current.VersionCode -cne $journal.VersionCode) { throw 'Installed version changed or the app is missing. This journal cannot safely roll back the current state.' }
    if ($journal.HomeRequested) {
        $resolvedHome = Get-CurrentHome $adb $target.Serial
        if ($resolvedHome -cne $script:Component -and $resolvedHome -cne ($script:Package + '/.MainActivity') -and $resolvedHome -cne $journal.Previous.HomeComponent) {
            throw 'HOME was changed by another action after this transaction. Automatic rollback is refused.'
        }
        $mutating = $true
        $homeOutput = Invoke-TargetAdb $adb $target.Serial @('shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $journal.Previous.HomeComponent)
        if ($homeOutput -notmatch '(?i)success') { throw "Previous HOME was not confirmed: $homeOutput" }
        if ((Get-CurrentHome $adb $target.Serial) -cne $journal.Previous.HomeComponent) { throw 'Previous HOME did not resolve after rollback.' }
        $stateBeforeRestore = Get-PackageSnapshot $adb $target.Serial
        if (!$stateBeforeRestore.Installed -or $stateBeforeRestore.VersionCode -cne $journal.VersionCode) { throw 'Installed package/version changed during HOME rollback.' }
        if ($stateBeforeRestore.ComponentState -ne $journal.Previous.ComponentState) {
            $stateCommand = @('default-state', 'enable', 'disable')[[int]$journal.Previous.ComponentState]
            $stateName = @('default', 'enabled', 'disabled')[[int]$journal.Previous.ComponentState]
            $stateOutput = Invoke-TargetAdb $adb $target.Serial @('shell', 'pm', $stateCommand, '--user', '0', $script:Component)
            if ($stateOutput -notmatch ('new state:\s*' + $stateName + '\s*$')) { throw "Component state restore was not confirmed: $stateOutput" }
        } else { Write-Output "Component override already matches captured state=$($journal.Previous.ComponentState); no state command needed." }
        $restored = Get-PackageSnapshot $adb $target.Serial
        if (!$restored.Installed -or $restored.VersionCode -cne $journal.VersionCode) { throw 'Installed package/version changed during component rollback.' }
        if ($restored.ComponentState -ne $journal.Previous.ComponentState) { throw 'Component override did not match the captured state after rollback.' }
    }
    $journal.Status = 'RolledBack'; Write-InstallJournal $journalFile $journal
    Write-Output 'Rollback complete. Previous HOME/component override restored when requested. APK and app data retained.'
    exit 0
} catch {
    if ($mutating -and $null -ne $journal) {
        $journal.Status = 'RollbackFailed'
        try { Write-InstallJournal $journalFile $journal } catch { Write-Warning 'Could not update rollback status; retain the original journal.' }
    }
    [Console]::Error.WriteLine('Eve Box rollback: ' + $_.Exception.Message)
    exit 1
}
