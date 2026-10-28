#requires -Version 5.1
# Test-owned console double. The production entry point receives ordinary CLI
# parameters; only Read-Host is replaced, inside this isolated child process.
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ParametersPath,
      [Parameter(Mandatory=$true)][string]$ResponsesPath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Text.UTF8Encoding]::new($false)
if ($env:SOFTLUMI_TV_SETUP_TEST_MARKER -cne 'isolated-offline-fixture-v1' -or
    !$env:SOFTLUMI_TV_SETUP_TEST_STATE) { throw 'Explicit isolated EVE fixture required.' }
$global:EveTestStatePath = [IO.Path]::GetFullPath($env:SOFTLUMI_TV_SETUP_TEST_STATE)
$global:EveTestCaseRoot = [IO.Path]::GetDirectoryName($global:EveTestStatePath)
foreach ($path in @($ParametersPath, $ResponsesPath)) {
    $full = [IO.Path]::GetFullPath($path)
    if (!$full.StartsWith($global:EveTestCaseRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'EVE input files must belong to the controlled case.'
    }
}
$rawParameters = Get-Content -LiteralPath $ParametersPath -Raw -Encoding UTF8 | ConvertFrom-Json
$parameters = @{}
foreach ($property in $rawParameters.PSObject.Properties) { $parameters[$property.Name] = $property.Value }
$global:EveTestKitRoot = [IO.Path]::GetFullPath([string]$parameters.KitRoot)
if (!$global:EveTestKitRoot.StartsWith($global:EveTestCaseRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'KitRoot must belong to this controlled case.'
}
$responseDocument = Get-Content -LiteralPath $ResponsesPath -Raw -Encoding UTF8 | ConvertFrom-Json
$global:EveTestResponses = @($responseDocument.responses)
$global:EveTestResponseIndex = 0
function global:Read-Host {
    param([string]$Prompt)
    $response = $null; $action = ''; $answer = $null
    $eof = $global:EveTestResponseIndex -ge $global:EveTestResponses.Count
    if (!$eof) {
        $response = $global:EveTestResponses[$global:EveTestResponseIndex]
        if ($response -is [string]) { $answer = $response }
        elseif ($null -ne $response) { $action = [string]$response.action; $answer = $response.answer }
    }
    $global:EveTestResponseIndex++
    $event = [ordered]@{ index=$global:EveTestResponseIndex; prompt=$Prompt; eof=$eof; action=$action }
    if ($action -in @('TamperPlan','TamperJournal')) {
        $privateRoot = Join-Path $global:EveTestKitRoot 'setup-private'
        $filename = if ($action -eq 'TamperPlan') { 'setup-plan.json' } else { 'setup-journal.json' }
        $plans = @(Get-ChildItem -LiteralPath $privateRoot -Filter $filename -File -Recurse)
        if ($plans.Count -ne 1) { throw 'Exactly one owned preview file is required for tampering.' }
        $plan = $plans[0]
        if (($plan.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            !$plan.FullName.StartsWith($privateRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing a redirected preview plan.'
        }
        $event.beforeSha256 = (Get-FileHash -LiteralPath $plan.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        [IO.File]::AppendAllText($plan.FullName, "`n", [Text.UTF8Encoding]::new($false))
        $event.afterSha256 = (Get-FileHash -LiteralPath $plan.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    } elseif ($action -eq 'HoldConfirmation') {
        $ready = Join-Path $global:EveTestCaseRoot 'wizard-lock-ready.marker'
        $release = Join-Path $global:EveTestCaseRoot 'wizard-lock-release.marker'
        [IO.File]::WriteAllText($ready, 'owned-wizard-at-confirmation')
        $deadline = [DateTime]::UtcNow.AddSeconds(45)
        while (![IO.File]::Exists($release) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 25 }
        if (![IO.File]::Exists($release)) { throw 'Controlled wizard confirmation gate timed out.' }
        $event.gateReleased = $true
    } elseif ($action -eq 'CreatePendingAtConfirmation') {
        $plans = @(Get-ChildItem -LiteralPath (Join-Path $global:EveTestKitRoot 'setup-private') -Filter 'setup-plan.json' -File -Recurse)
        if ($plans.Count -ne 1) { throw 'Exactly one first-wizard plan is required for the external actor.' }
        $firstPlan = Get-Content -LiteralPath $plans[0].FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        $externalRoot = Join-Path (Join-Path $global:EveTestKitRoot 'setup-private') ([guid]::NewGuid().ToString('N'))
        [void][IO.Directory]::CreateDirectory($externalRoot)
        $externalPlan = Join-Path $externalRoot 'setup-plan.json'
        $externalJournal = Join-Path $externalRoot 'setup-journal.json'
        $backendModule = Import-Module (Join-Path $PSScriptRoot '../TvSetup.Common.psm1') -PassThru -DisableNameChecking
        $externalOptions = @{
            Serial=$firstPlan.target.serial;ExpectedModel=$firstPlan.target.model;ExpectedFingerprint=$firstPlan.target.fingerprint;
            Emulator=[bool]$firstPlan.target.emulator;AdbPath=$firstPlan.tools.adbPath;AdbServerPort=$firstPlan.target.adbServerPort;
            CommandTimeoutSeconds=10;InstallTimeoutSeconds=10;PinnedRelease='candidate.14';Apk=$firstPlan.apk.sourcePath;
            ApkSha256='';SigningCertSha256='';ApkSignerPath='no-sdk-signer';AaptPath='no-sdk-aapt';ProfileId=$firstPlan.profile.id;
            SetHome=$false;DisableStockHome=$false;DisablePackages=@();PlanPath=$externalPlan;JournalPath=$externalJournal;ApprovedPlanSha256=''
        }
        [void](& $backendModule { param($options) Invoke-TvSetup 'Plan' $options } $externalOptions)
        $externalOptions.ApprovedPlanSha256 = (Get-FileHash -LiteralPath $externalPlan -Algorithm SHA256).Hash.ToLowerInvariant()
        $expectedUnknown = $false
        try { [void](& $backendModule { param($options) Invoke-TvSetup 'Apply' $options } $externalOptions) }
        catch {
            if ([IO.File]::Exists($externalJournal)) {
                $pending = Get-Content -LiteralPath $externalJournal -Raw -Encoding UTF8 | ConvertFrom-Json
                $expectedUnknown = $pending.status -ceq 'NeedsReconcile' -and $pending.actions[0].status -ceq 'NeedsReconcile'
            }
            if (!$expectedUnknown) { throw }
        }
        if (!$expectedUnknown) { throw 'External actor did not create a real unknown-install journal.' }
        $callText = [IO.File]::ReadAllText($global:EveTestStatePath + '.calls')
        $injectedCalls = @($callText -split '\r?\n' | Where-Object { $_ } | ForEach-Object { $_ | ConvertFrom-Json })
        $event.externalInstallCount = @($injectedCalls | Where-Object { $_.Args.Count -ge 5 -and $_.Args[4] -ceq 'install' }).Count
        $event.externalOutcomeUnknown = $true
        [IO.File]::WriteAllText(($global:EveTestStatePath + '.external-calls'), $callText, [Text.UTF8Encoding]::new($false))
        # Following calls belong to the waiting wizard, which must not retry.
        [IO.File]::WriteAllText(($global:EveTestStatePath + '.calls'), '', [Text.UTF8Encoding]::new($false))
    } elseif ($action -eq 'ChangeFingerprint') {
        $state = Get-Content -LiteralPath $global:EveTestStatePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $state.Fingerprint = 'Example/changed/device:14/test/changed:user/release-keys'
        [IO.File]::WriteAllText($global:EveTestStatePath, ($state | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
    } elseif ($action) { throw 'Unknown EVE console fixture action.' }
    [IO.File]::AppendAllText(($global:EveTestStatePath + '.answers'), (($event | ConvertTo-Json -Compress) + "`n"), [Text.UTF8Encoding]::new($false))
    if ($eof -or $null -eq $answer) { return $null }
    return [string]$answer
}
$entry = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\setup-eve-box.ps1'))
& $entry @parameters
exit $LASTEXITCODE
