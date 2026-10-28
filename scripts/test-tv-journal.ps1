#requires -Version 5.1
<# .SYNOPSIS
Host-only atomic journal tests. Uses real local files and no-delete read handles;
does not invoke ADB, SDK tools, network, or any Android operation.
#>
[CmdletBinding()]
param([string]$ReportPath = '')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'windows_file_lock_tests_required' }
$backendPath = Join-Path $PSScriptRoot 'TvSetup.Common.psm1'
$backend = Import-Module $backendPath -Force -DisableNameChecking -PassThru
$marker = [guid]::NewGuid().ToString('N')
$fixtureParent = [IO.Path]::GetFullPath($PSScriptRoot)
$fixture = Join-Path $fixtureParent ('.tv-journal-test-' + $marker)
[void][IO.Directory]::CreateDirectory($fixture)
[IO.File]::WriteAllText((Join-Path $fixture '.owned-fixture'), $marker)
$results = @(); $caseNumber = 0

function Assert-That([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Expected-Bytes($Value) { return ,([Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 40) + "`n")) }
function Same-Bytes([byte[]]$Left, [byte[]]$Right) { return ([Convert]::ToBase64String($Left) -ceq [Convert]::ToBase64String($Right)) }
function Assert-Content([string]$Path, [byte[]]$Bytes) { Assert-That (Same-Bytes ([IO.File]::ReadAllBytes($Path)) $Bytes) 'unexpected_file_bytes' }
function Assert-NoTemporary {
    Assert-That (@(Get-ChildItem -LiteralPath $fixture -File -Filter '*.tmp').Count -eq 0) 'temporary_file_not_cleaned'
}
function Find-IoException($Exception) {
    while ($null -ne $Exception -and $Exception -isnot [IO.IOException]) { $Exception = $Exception.InnerException }
    return $Exception
}
function Invoke-Write([string]$Path, $Value, $Observation, $ReleaseHandle = $null, [switch]$Create) {
    & $backend { param($file, $data, $createFile) Write-TvJson $file $data -Create:$createFile -Verbose } $Path $Value $Create.IsPresent 4>&1 |
        ForEach-Object {
            if ($_ -is [Management.Automation.VerboseRecord]) {
                $Observation.Retries++
                if ($null -ne $ReleaseHandle -and $Observation.Retries -eq 1) { $ReleaseHandle.Dispose(); $Observation.Released = $true }
            } else { throw 'unexpected_writer_output' }
        }
}
function With-InjectedReplacement($Probe, [scriptblock]$Body) {
    $original = & $backend {
        param($testProbe)
        $saved = (Get-Item Function:Invoke-TvJsonReplace).ScriptBlock
        $script:TvJournalTestProbe = $testProbe
        Set-Item Function:script:Invoke-TvJsonReplace -Value {
            param([string]$Temporary, [string]$Target)
            $script:TvJournalTestProbe.Calls++
            switch ($script:TvJournalTestProbe.Mode) {
                'Unknown' { throw $script:TvJournalTestProbe.Error }
                'TargetDrift' { [IO.File]::WriteAllBytes($Target, $script:TvJournalTestProbe.Bytes) }
                'TemporaryDrift' { [IO.File]::WriteAllBytes($Temporary, $script:TvJournalTestProbe.Bytes) }
                'AlreadyCommitted' { [IO.File]::Replace($Temporary, $Target, [Management.Automation.Language.NullString]::Value) }
                default { throw 'invalid_test_injection' }
            }
            throw [IO.IOException]::new('Controlled replacement acknowledgement failure.', -2147024864)
        }
        return $saved
    } $Probe
    try { & $Body }
    finally {
        & $backend {
            param($saved)
            Set-Item Function:script:Invoke-TvJsonReplace -Value $saved
            Remove-Variable -Name TvJournalTestProbe -Scope Script
        } $original
    }
}
function Test-Case([string]$Name, [scriptblock]$Body) {
    $script:caseNumber++
    $path = Join-Path $fixture ('case-' + $script:caseNumber + '.json')
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $detail = & $Body $path
        Assert-NoTemporary
        $script:results += [pscustomobject]@{ Name = $Name; Passed = $true; Seconds = $watch.Elapsed.TotalSeconds; Details = $detail; Error = $null }
        Write-Output ('PASS ' + $Name)
    } catch {
        $script:results += [pscustomobject]@{ Name = $Name; Passed = $false; Seconds = $watch.Elapsed.TotalSeconds; Details = $null; Error = $_.Exception.Message }
        Write-Output ('FAIL ' + $Name + ': ' + $_.Exception.Message)
    }
}

try {
    Test-Case 'normal-create-repeated-replacement-and-non-ascii' {
        param($path)
        $text = ([string][char]0x0416) + [char]0x00e9 + [char]0x65e5 + [char]0x672c + [char]0x8a9e + [char]::ConvertFromUtf32(0x1f680)
        $observation = [pscustomobject]@{ Retries = 0; Released = $false }
        Invoke-Write $path ([pscustomobject]@{ value = $text; revision = 0 }) $observation -Create
        for ($revision = 1; $revision -le 12; $revision++) {
            $value = [pscustomobject]@{ value = $text; revision = $revision }
            Invoke-Write $path $value $observation
            Assert-Content $path (Expected-Bytes $value)
        }
        Assert-That ($observation.Retries -eq 0) 'normal_write_retried'
        $decoded = ConvertFrom-Json ([IO.File]::ReadAllText($path, [Text.Encoding]::UTF8))
        Assert-That ($decoded.value -ceq $text -and $decoded.revision -eq 12) 'non_ascii_roundtrip_failed'
        [pscustomobject]@{ Replacements = 12; Retries = $observation.Retries }
    }
    Test-Case 'real-no-delete-handle-released-after-first-refusal' {
        param($path)
        [IO.File]::WriteAllText($path, '{"state":"before"}')
        $value = [pscustomobject]@{ state = 'intended' }
        $observation = [pscustomobject]@{ Retries = 0; Released = $false }
        $heldRead = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        try { Invoke-Write $path $value $observation $heldRead } finally { $heldRead.Dispose() }
        Assert-That ($observation.Released -and $observation.Retries -eq 1) 'short_lock_did_not_exercise_one_retry'
        Assert-Content $path (Expected-Bytes $value)
        [pscustomobject]@{ Retries = $observation.Retries; ReleasedOnRetry = $observation.Released }
    }
    Test-Case 'permanent-no-delete-handle-refuses-and-preserves-old-bytes' {
        param($path)
        $before = [Text.Encoding]::UTF8.GetBytes('{"state":"before"}')
        [IO.File]::WriteAllBytes($path, $before)
        $observation = [pscustomobject]@{ Retries = 0; Released = $false }
        $heldRead = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        $failure = $null
        try { try { Invoke-Write $path ([pscustomobject]@{ state = 'intended' }) $observation } catch { $failure = Find-IoException $_.Exception } }
        finally { $heldRead.Dispose() }
        Assert-That ($null -ne $failure -and ($failure.HResult -band 65535) -in @(32, 33, 1175)) 'permanent_lock_error_not_preserved'
        Assert-That ($observation.Retries -eq 2) 'permanent_lock_retry_budget_changed'
        Assert-Content $path $before
        [pscustomobject]@{ Retries = $observation.Retries; HResult = $failure.HResult; Win32 = ($failure.HResult -band 65535) }
    }
    Test-Case 'unknown-io-is-rethrown-without-retry' {
        param($path)
        $before = [Text.Encoding]::UTF8.GetBytes('{"state":"before"}')
        [IO.File]::WriteAllBytes($path, $before)
        $observation = [pscustomobject]@{ Retries = 0; Released = $false }
        $probe = [pscustomobject]@{ Mode = 'Unknown'; Calls = 0; Error = [IO.IOException]::new('Controlled unknown I/O error.'); Bytes = $null }
        $capture = [pscustomobject]@{ Error = $null }
        With-InjectedReplacement $probe { try { Invoke-Write $path ([pscustomobject]@{ state = 'intended' }) $observation } catch { $capture.Error = Find-IoException $_.Exception } }
        Assert-That ([object]::ReferenceEquals($capture.Error, $probe.Error)) 'original_unknown_exception_not_preserved'
        Assert-That ($probe.Calls -eq 1 -and $observation.Retries -eq 0) 'unknown_io_was_retried'
        Assert-Content $path $before
        [pscustomobject]@{ ReplaceCalls = $probe.Calls; Retries = $observation.Retries; HResult = $capture.Error.HResult }
    }
    foreach ($mode in @('TargetDrift', 'TemporaryDrift')) {
        Test-Case ('changed-bytes-refuse-retry-' + $mode) {
            param($path)
            $before = [Text.Encoding]::UTF8.GetBytes('{"state":"before"}')
            $foreign = [Text.Encoding]::UTF8.GetBytes('{"state":"foreign"}')
            [IO.File]::WriteAllBytes($path, $before)
            $observation = [pscustomobject]@{ Retries = 0; Released = $false }
            $probe = [pscustomobject]@{ Mode = $mode; Calls = 0; Error = $null; Bytes = $foreign }
            $capture = [pscustomobject]@{ Error = $null }
            With-InjectedReplacement $probe { try { Invoke-Write $path ([pscustomobject]@{ state = 'intended' }) $observation } catch { $capture.Error = Find-IoException $_.Exception } }
            Assert-That ($null -ne $capture.Error -and $probe.Calls -eq 1 -and $observation.Retries -eq 0) 'changed_bytes_were_retried'
            if ($mode -ceq 'TargetDrift') { Assert-Content $path $foreign } else { Assert-Content $path $before }
            [pscustomobject]@{ ReplaceCalls = $probe.Calls; Retries = $observation.Retries }
        }
    }
    Test-Case 'already-committed-intended-bytes-confirm-success' {
        param($path)
        [IO.File]::WriteAllText($path, '{"state":"before"}')
        $observation = [pscustomobject]@{ Retries = 0; Released = $false }
        $probe = [pscustomobject]@{ Mode = 'AlreadyCommitted'; Calls = 0; Error = $null; Bytes = $null }
        $value = [pscustomobject]@{ state = 'intended' }
        With-InjectedReplacement $probe { Invoke-Write $path $value $observation }
        Assert-That ($probe.Calls -eq 1 -and $observation.Retries -eq 0) 'committed_bytes_were_replaced_again'
        Assert-Content $path (Expected-Bytes $value)
        [pscustomobject]@{ ReplaceCalls = $probe.Calls; Retries = $observation.Retries }
    }
    $failed = @($results | Where-Object { !$_.Passed }).Count
    $summary = [ordered]@{ SchemaVersion = 1; HostOnly = $true; AndroidToolsInvoked = $false; PowerShellVersion = $PSVersionTable.PSVersion.ToString(); CommonSha256 = (Get-FileHash -LiteralPath $backendPath -Algorithm SHA256).Hash.ToLowerInvariant(); CaseCount = $results.Count; Passed = $results.Count - $failed; Failed = $failed; Results = $results }
    if ($ReportPath) {
        $report = [IO.Path]::GetFullPath($ReportPath)
        [void][IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($report))
        [IO.File]::WriteAllText($report, ($summary | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    }
    Write-Output ('Host journal tests: {0} passed, {1} failed.' -f ($results.Count - $failed), $failed)
    if ($failed) { throw 'host_journal_tests_failed' }
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $item = Get-Item -LiteralPath $resolved -Force
    if (![string]::Equals([IO.Path]::GetDirectoryName($resolved), $fixtureParent, [StringComparison]::OrdinalIgnoreCase) -or
        !$item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        [IO.File]::ReadAllText((Join-Path $resolved '.owned-fixture')) -cne $marker -or
        @(Get-ChildItem -LiteralPath $resolved -Force | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -ne 0) { throw 'unsafe_journal_fixture_cleanup' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
