Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:Package = 'io.github.ekkzy.evebox'
$script:Component = 'io.github.ekkzy.evebox/io.github.ekkzy.evebox.MainActivity'

function Invoke-CheckedTool {
    param([string]$Tool, [string[]]$Arguments)
    if (!(Test-Path -LiteralPath $Tool -PathType Leaf)) { throw "External tool file is missing: $Tool" }
    $global:LASTEXITCODE = 0
    # Windows PowerShell converts native stderr to ErrorRecord even for warnings.
    # Preserve it as output, then judge the actual process exit status.
    $savedPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = @(& $Tool @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedPreference }
    $output = ($result | ForEach-Object { "$_" }) -join "`n"
    if ($code -ne 0) { throw "External tool failed (exit $code): $Tool $($Arguments -join ' ')`n$output" }
    return $output.Trim()
}

function Resolve-InstallerTool {
    param([string]$Name)
    $command = Get-Command $Name -ErrorAction Stop | Select-Object -First 1
    if ($command.CommandType -notin @('Application', 'ExternalScript')) { throw "Tool must be an executable or script: $Name" }
    return $command.Source
}

function Assert-HexDigest {
    param([string]$Digest, [string]$Label)
    if ($Digest -notmatch '^(?:[a-fA-F0-9]{64}|(?:[a-fA-F0-9]{2}:){31}[a-fA-F0-9]{2})$') {
        throw "$Label must be an explicit SHA-256 digest (64 hex characters)."
    }
    return $Digest.Replace(':', '').ToLowerInvariant()
}

function Assert-HomeComponent {
    param([string]$Component)
    if ($Component -notmatch '^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+/(?:\.)?[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$') {
        throw "Unsupported or missing HOME component: $Component"
    }
    if ($Component.StartsWith('android/')) { throw 'The system HOME chooser is not a captured default HOME. Choose a default manually first.' }
    return $Component
}

function Get-TargetRequest {
    param([string]$EmulatorSerial, [bool]$StageBPhysicalDevice, [string]$DeviceSerial,
          [string]$ExpectedModel, [string]$ExpectedBuildFingerprint)
    if ($StageBPhysicalDevice) {
        if ($EmulatorSerial) { throw 'Select exactly one target: use DeviceSerial for Stage B or EmulatorSerial for Stage A.' }
        if (!$DeviceSerial -or !$ExpectedModel -or !$ExpectedBuildFingerprint) {
            throw 'Stage B requires DeviceSerial, ExpectedModel, and ExpectedBuildFingerprint from informed device selection.'
        }
        if ($DeviceSerial -notmatch '^[A-Za-z0-9][A-Za-z0-9._:\[\]-]*$' -or $DeviceSerial -match '^emulator-') {
            throw 'Stage B DeviceSerial must explicitly identify one physical target.'
        }
        return [pscustomobject]@{ Serial = $DeviceSerial; Kind = 'physical'; Model = $ExpectedModel; Fingerprint = $ExpectedBuildFingerprint }
    }
    if ($DeviceSerial -or $ExpectedModel -or $ExpectedBuildFingerprint) { throw 'Physical target arguments require the explicit StageBPhysicalDevice flag.' }
    if ($EmulatorSerial -notmatch '^emulator-[0-9]+$') {
        throw 'Explicit EmulatorSerial is required (for example emulator-5554). No target is selected automatically, even if several are connected.'
    }
    return [pscustomobject]@{ Serial = $EmulatorSerial; Kind = 'emulator'; Model = ''; Fingerprint = '' }
}

function Invoke-TargetAdb {
    param([string]$Adb, [string]$Serial, [string[]]$Arguments)
    return Invoke-CheckedTool -Tool $Adb -Arguments (@('-s', $Serial) + $Arguments)
}

function Confirm-Target {
    param([string]$Adb, $Request)
    $state = Invoke-TargetAdb $Adb $Request.Serial @('get-state')
    if ($state -ne 'device') { throw "Selected target is not ready: $state. Authorize RSA/pairing manually when applicable." }
    $qemu = Invoke-TargetAdb $Adb $Request.Serial @('shell', 'getprop', 'ro.kernel.qemu')
    if ($Request.Kind -eq 'emulator' -and $qemu -ne '1') { throw 'Selected emulator serial did not report ro.kernel.qemu=1. No mutation was attempted.' }
    if ($Request.Kind -eq 'physical' -and $qemu -notin @('', '0')) { throw 'Stage B target reported an emulator or an unexpected qemu property.' }
    $model = Invoke-TargetAdb $Adb $Request.Serial @('shell', 'getprop', 'ro.product.model')
    $fingerprint = Invoke-TargetAdb $Adb $Request.Serial @('shell', 'getprop', 'ro.build.fingerprint')
    if (!$model -or !$fingerprint) { throw 'Target identity is incomplete; model and build fingerprint are required.' }
    if ($Request.Kind -eq 'physical' -and ($model -cne $Request.Model -or $fingerprint -cne $Request.Fingerprint)) {
        throw 'The selected physical target does not match the expected model/build fingerprint. No mutation was attempted.'
    }
    $userId = Invoke-TargetAdb $Adb $Request.Serial @('shell', 'am', 'get-current-user')
    if ($userId -ne '0') { throw 'Only the primary Android user (0) is supported. Switch users manually before continuing.' }
    return [pscustomobject]@{ Serial = $Request.Serial; Kind = $Request.Kind; Model = $model; Fingerprint = $fingerprint; UserId = 0 }
}

function Get-ValidatedApk {
    param([string]$Apk, [string]$Sha256, [string]$SigningCertSha256, [string]$ApkSigner, [string]$Aapt)
    $expectedHash = Assert-HexDigest $Sha256 'ApkSha256'
    $expectedCert = Assert-HexDigest $SigningCertSha256 'SigningCertSha256'
    $source = Get-Item -LiteralPath $Apk -ErrorAction Stop
    if ($source.PSIsContainer -or $source.Extension -ine '.apk') { throw 'Apk must name an existing APK file.' }
    $temp = Join-Path ([IO.Path]::GetTempPath()) ('eve-box-verified-' + [guid]::NewGuid().ToString('N'))
    [void](New-Item -ItemType Directory -Path $temp)
    $copy = Join-Path $temp 'verified.apk'
    try {
        Copy-Item -LiteralPath $source.FullName -Destination $copy
        $actualHash = (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -cne $expectedHash) { throw 'APK SHA-256 mismatch. No ADB command was run.' }
        $signature = Invoke-CheckedTool $ApkSigner @('verify', '--verbose', '--print-certs', $copy)
        $matches = [regex]::Matches($signature, '(?m)^Signer #[0-9]+ certificate SHA-256 digest:\s*([A-Fa-f0-9:]+)\s*$')
        if ($matches.Count -ne 1) { throw 'Exactly one verified APK signer is required; signer certificate output is missing or ambiguous.' }
        $actualCert = Assert-HexDigest $matches[0].Groups[1].Value 'Verified signing certificate'
        if ($actualCert -cne $expectedCert) { throw 'APK signing certificate SHA-256 mismatch. No ADB command was run.' }
        $metadata = Invoke-CheckedTool $Aapt @('dump', 'badging', $copy)
        $packageMatch = [regex]::Match($metadata, "(?m)^package: name='([^']+)' versionCode='([0-9]+)'")
        if (!$packageMatch.Success -or $packageMatch.Groups[1].Value -cne $script:Package) { throw "APK package mismatch; expected $script:Package. No ADB command was run." }
        $version = $packageMatch.Groups[2].Value
        if ([long]$version -lt 1) { throw 'APK versionCode must be positive.' }
        $activities = [regex]::Matches($metadata, "(?m)^(?:leanback-)?launchable-activity: name='([^']+)'")
        $mainFound = @($activities | Where-Object { $_.Groups[1].Value -ceq ($script:Package + '.MainActivity') }).Count -gt 0
        if (!$mainFound) { throw 'APK MainActivity launcher metadata is missing or unexpected.' }
        return [pscustomobject]@{ File = $copy; TemporaryDirectory = $temp; Sha256 = $actualHash; CertSha256 = $actualCert; VersionCode = $version }
    } catch {
        Remove-Item -LiteralPath $copy -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
        throw
    }
}

function Remove-ValidatedApk {
    param($ValidatedApk)
    if ($null -ne $ValidatedApk) {
        Remove-Item -LiteralPath $ValidatedApk.File -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $ValidatedApk.TemporaryDirectory -Force -ErrorAction SilentlyContinue
    }
}

function Get-PackageSnapshot {
    param([string]$Adb, [string]$Serial)
    $paths = Invoke-TargetAdb $Adb $Serial @('shell', 'pm', 'list', 'packages', '--user', '0', $script:Package)
    $installed = @($paths -split '\r?\n' | Where-Object { $_ -ceq "package:$script:Package" }).Count -eq 1
    if (!$installed) { return [pscustomobject]@{ Installed = $false; VersionCode = $null; ComponentState = 0 } }
    $dump = Invoke-TargetAdb $Adb $Serial @('shell', 'dumpsys', 'package', $script:Package)
    $version = [regex]::Match($dump, '(?m)^\s*versionCode=([0-9]+)\b')
    if (!$version.Success) { throw 'Cannot read the installed package versionCode.' }
    # dumpsys keeps per-user component overrides in explicit enabled/disabled lists.
    $userMatch = [regex]::Match($dump, '(?ms)^\s*User 0:([^\r\n]*)(.*?)(?=^\s*User [0-9]+:|\z)')
    if (!$userMatch.Success -or $userMatch.Groups[1].Value -notmatch '\binstalled=true\b') { throw 'Cannot capture package state for Android user 0.' }
    $body = $userMatch.Groups[2].Value
    $class = $script:Package + '.MainActivity'
    $shortClass = '.MainActivity'
    $componentState = 0
    foreach ($entry in @(@{ Name = 'enabledComponents'; Value = 1 }, @{ Name = 'disabledComponents'; Value = 2 })) {
        $section = [regex]::Match($body, ('(?ms)^([ \t]*)' + $entry.Name + ':\s*\r?\n((?:(?:\1[ \t]+)[^\r\n]*\r?\n?)*)'))
        if ($section.Success) {
            $items = @($section.Groups[2].Value -split '\r?\n' | ForEach-Object { $_.Trim() })
            if ($items -ccontains $class -or $items -ccontains $shortClass) { $componentState = $entry.Value }
        }
    }
    return [pscustomobject]@{ Installed = $true; VersionCode = $version.Groups[1].Value; ComponentState = $componentState }
}

function Get-CurrentHome {
    param([string]$Adb, [string]$Serial)
    $output = Invoke-TargetAdb $Adb $Serial @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    $lines = @($output -split '\r?\n' | Where-Object { $_.Trim() -match '^[A-Za-z_][A-Za-z0-9_.]*/\.?[A-Za-z_][A-Za-z0-9_.]*$' })
    if ($lines.Count -ne 1) { throw 'Cannot capture a single previous HOME. Choose a default manually before requesting SetHome.' }
    return Assert-HomeComponent $lines[0].Trim()
}

function Write-InstallJournal {
    param([string]$Path, $Journal, [switch]$Create)
    $Journal.UpdatedUtc = [DateTime]::UtcNow.ToString('o')
    $parent = Split-Path -Parent $Path
    if (!(Test-Path -LiteralPath $parent -PathType Container)) { [void](New-Item -ItemType Directory -Path $parent -Force) }
    $temporary = $Path + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllText($temporary, ($Journal | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
        if ($Create) {
            # The initial journal must never replace an existing transaction,
            # including one created after the installer's first path check.
            [IO.File]::Move($temporary, $Path)
        } else { Move-Item -LiteralPath $temporary -Destination $Path -Force }
    } finally {
        if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
    }
}

function Read-InstallJournal {
    param([string]$Path, $Request)
    $file = Get-Item -LiteralPath $Path -ErrorAction Stop
    if ($file.PSIsContainer -or $file.Length -gt 65536) { throw 'Invalid installer journal file.' }
    $journal = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
    if (($null -ne $journal.PSObject.Properties['Tool'] -and $journal.Tool -ceq 'softlumi-installer') -or
        ($null -ne $journal.PSObject.Properties['Package'] -and $journal.Package -ceq 'io.github.ekkzy.softlumi')) {
        throw 'This journal belongs to the legacy io.github.ekkzy.softlumi app. Use the original setup kit that created it for rollback. The old app and journal have not been changed.'
    }
    if ($journal.SchemaVersion -ne 1 -or $journal.Tool -cne 'eve-box-installer' -or $journal.Package -cne $script:Package -or $journal.Component -cne $script:Component) { throw 'Unsupported or invalid installer journal.' }
    if ($journal.TransactionId -notmatch '^[0-9a-f]{32}$') { throw 'Invalid journal transaction ID.' }
    if ($journal.Target.Serial -cne $Request.Serial -or $journal.Target.Kind -cne $Request.Kind -or $journal.Target.UserId -ne 0) { throw 'Journal target does not match the explicitly selected target.' }
    if (!$journal.Target.Model -or !$journal.Target.Fingerprint) { throw 'Journal target identity is missing.' }
    # PowerShell 7 can deserialize JSON ISO timestamps as DateTime, whereas
    # Windows PowerShell 5.1 leaves strings. Avoid culture-dependent ToString().
    if ($journal.CreatedUtc -is [DateTime] -or $journal.CreatedUtc -is [DateTimeOffset]) {
        $created = [DateTimeOffset]$journal.CreatedUtc
    } else {
        $created = [DateTimeOffset]::Parse([string]$journal.CreatedUtc, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind)
    }
    $age = [DateTimeOffset]::UtcNow - $created
    if ($age.TotalDays -gt 14 -or $age.TotalMinutes -lt -5) { throw 'Journal is stale or has a future timestamp. Inspect state manually; automatic rollback is refused.' }
    if ($journal.Status -notin @('Installed', 'ChangingHome', 'HomeChanged', 'HomeChangeFailed', 'RollbackFailed')) { throw "Journal status cannot be rolled back: $($journal.Status)" }
    if ($journal.HomeRequested -isnot [bool] -or $journal.Previous.Installed -isnot [bool] -or
        ($journal.Previous.ComponentState -isnot [int] -and $journal.Previous.ComponentState -isnot [long]) -or
        $journal.Previous.ComponentState -notin @(0, 1, 2)) { throw 'Journal component state is invalid.' }
    if ($journal.HomeRequested) { [void](Assert-HomeComponent $journal.Previous.HomeComponent) }
    if (!(Assert-HexDigest $journal.ApkSha256 'Journal APK hash') -or !(Assert-HexDigest $journal.SigningCertSha256 'Journal certificate hash')) { throw 'Invalid journal digest.' }
    if ($journal.VersionCode -notmatch '^[1-9][0-9]*$') { throw 'Invalid journal APK version.' }
    return $journal
}

function Assert-JournalIdentity {
    param($Journal, $Target)
    if ($Target.Model -cne $Journal.Target.Model -or $Target.Fingerprint -cne $Journal.Target.Fingerprint) { throw 'Target model/build changed since the journal was written. Automatic rollback is refused.' }
}

Export-ModuleMember -Function *
