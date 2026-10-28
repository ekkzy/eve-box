#requires -Version 5.1
<#
.SYNOPSIS
Interactive Windows installer for the pinned EVE-BOX setup kit.
.DESCRIPTION
Uses the existing TV setup transaction backend. No downloads or automatic
approvals. PreviewOnly creates a read-only plan; Inspect never changes the TV.
#>
[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$AdbPath = '',
    [ValidateRange(1024, 65535)][int]$AdbServerPort = 5037,
    [ValidateSet('Install', 'Inspect', 'Restore')][string]$Mode = 'Install',
    [string]$JournalPath = '',
    [switch]$Emulator,
    [switch]$PreviewOnly,
    [string]$KitRoot = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
try {
    Import-Module (Join-Path $PSScriptRoot 'EveSetup.Wizard.psm1') -Force -DisableNameChecking
    $wizardOptions = @{
        Serial = $Serial; AdbPath = $AdbPath; AdbServerPort = $AdbServerPort
        AdbServerPortSpecified = $PSBoundParameters.ContainsKey('AdbServerPort')
        Mode = $Mode; JournalPath = $JournalPath; Emulator = $Emulator.IsPresent
        PreviewOnly = $PreviewOnly.IsPresent; KitRoot = $KitRoot
    }
    $result = Invoke-EveSetupWizard $wizardOptions
    if ($result.status -eq 'Failed') { exit 1 }
    exit 0
} catch {
    [Console]::Error.WriteLine('EVE-BOX: ' + $_.Exception.Message)
    exit 1
}
