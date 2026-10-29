#requires -Version 5.1
<#
.SYNOPSIS
Plans, applies, inspects, or restores a reviewed Eve Box TV setup transaction.
.DESCRIPTION
Plan and Inspect run only read-only device commands. Apply requires the SHA-256
of the exact saved plan. No arbitrary package commands, downloads, uninstall,
downgrade, data clear, service configuration, or screen/sleep changes are allowed.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('Plan', 'Apply', 'Inspect', 'Restore')][string]$Mode,
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$ExpectedModel,
    [Parameter(Mandatory = $true)][string]$ExpectedFingerprint,
    [switch]$Emulator,
    [string]$AdbPath = 'adb',
    [ValidateRange(1024, 65535)][int]$AdbServerPort = 5037,
    [ValidateRange(1, 120)][int]$CommandTimeoutSeconds = 30,
    [ValidateRange(1, 300)][int]$InstallTimeoutSeconds = 180,
    [string]$Apk = '',
    [string]$ApkSha256 = '',
    [string]$SigningCertSha256 = '',
    [AllowEmptyString()][ValidateSet('', 'candidate.14', 'candidate.15')][string]$PinnedRelease = '',
    [string]$ApkSignerPath = 'apksigner',
    [string]$AaptPath = 'aapt',
    [ValidateSet('generic-install-only', 'mi-box-s3-android14')][string]$ProfileId = 'generic-install-only',
    [switch]$SetHome,
    [switch]$DisableStockHome,
    [string[]]$DisablePackages = @(),
    [string]$PlanPath = '',
    [string]$ApprovedPlanSha256 = '',
    [string]$JournalPath = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
try {
    Import-Module (Join-Path $PSScriptRoot 'TvSetup.Common.psm1') -Force -DisableNameChecking
    $options = @{
        Serial = $Serial; ExpectedModel = $ExpectedModel; ExpectedFingerprint = $ExpectedFingerprint; Emulator = $Emulator.IsPresent
        AdbPath = $AdbPath; AdbServerPort = $AdbServerPort; CommandTimeoutSeconds = $CommandTimeoutSeconds; InstallTimeoutSeconds = $InstallTimeoutSeconds
        Apk = $Apk; ApkSha256 = $ApkSha256; SigningCertSha256 = $SigningCertSha256; PinnedRelease = $PinnedRelease; ApkSignerPath = $ApkSignerPath; AaptPath = $AaptPath
        ProfileId = $ProfileId; SetHome = $SetHome.IsPresent; DisableStockHome = $DisableStockHome.IsPresent; DisablePackages = @($DisablePackages)
        PlanPath = $PlanPath; ApprovedPlanSha256 = $ApprovedPlanSha256; JournalPath = $JournalPath
    }
    Invoke-TvSetup $Mode $options
    exit 0
} catch {
    [Console]::Error.WriteLine('Eve Box setup: ' + $_.Exception.Message)
    exit 1
}
