Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:TvPackage = 'io.github.ekkzy.softlumi'
$script:TvComponent = 'io.github.ekkzy.softlumi/io.github.ekkzy.softlumi.MainActivity'
$script:TvToolName = 'softlumi-tv-setup'

function Assert-TvCondition($Condition, [string]$Code) {
    if (!$Condition) { throw $Code }
}
function Assert-TvKeys($Value, [string[]]$Keys) {
    Assert-TvCondition ($null -ne $Value -and $Value -is [pscustomobject]) 'invalid_json_object'
    $actual = @($Value.PSObject.Properties.Name)
    Assert-TvCondition ($actual.Count -eq $Keys.Count) 'invalid_json_keys'
    foreach ($key in $actual) { Assert-TvCondition ($Keys -ccontains $key) 'invalid_json_keys' }
}
function Assert-TvString($Value, [int]$Maximum = 512) {
    Assert-TvCondition ($Value -is [string] -and $Value.Length -gt 0 -and $Value.Length -le $Maximum -and $Value -notmatch '[\x00-\x1f\x7f]') 'invalid_string'
}
function Get-TvDigest([string]$Digest) {
    Assert-TvCondition ($Digest -match '^(?:[a-fA-F0-9]{64}|(?:[a-fA-F0-9]{2}:){31}[a-fA-F0-9]{2})$') 'invalid_sha256'
    return $Digest.Replace(':', '').ToLowerInvariant()
}
function Get-TvFileHash([string]$Path) {
    $stream = [IO.File]::OpenRead($Path)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose(); $stream.Dispose() }
}
function Assert-TvPackageName([string]$Name) {
    Assert-TvCondition ($Name.Length -le 200 -and $Name -cmatch '^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$') 'invalid_package_name'
}
function Get-TvComponent([string]$Value, [switch]$AllowChooser) {
    Assert-TvCondition ($Value.Length -le 400 -and $Value -cmatch '^([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+|android)/(\.?[A-Za-z_][A-Za-z0-9_.]*)$') 'invalid_home_component'
    $parts = $Value.Split('/')
    if (!$AllowChooser) { Assert-TvCondition ($parts[0] -cne 'android') 'home_chooser_requires_manual_selection' }
    if ($parts[1].StartsWith('.')) { return $parts[0] + '/' + $parts[0] + $parts[1] }
    return $Value
}
function Get-TvJsonPath([string]$Path) {
    Assert-TvString $Path 1024
    $full = [IO.Path]::GetFullPath($Path)
    Assert-TvCondition (!$full.StartsWith('\\') -and [IO.Path]::GetExtension($full) -ieq '.json') 'local_json_path_required'
    return $full
}
function Read-TvJson([string]$Path, [int]$Maximum = 1048576) {
    $full = Get-TvJsonPath $Path
    $file = Get-Item -LiteralPath $full -ErrorAction Stop
    Assert-TvCondition (!$file.PSIsContainer -and $file.Length -gt 0 -and $file.Length -le $Maximum -and !($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) 'invalid_json_file'
    $bytes = [IO.File]::ReadAllBytes($full)
    $encoding = [Text.UTF8Encoding]::new($false, $true)
    $text = $encoding.GetString($bytes).TrimStart([char]0xfeff)
    $value = ConvertFrom-Json -InputObject $text
    $hash = [Security.Cryptography.SHA256]::Create()
    try { $digest = ([BitConverter]::ToString($hash.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $hash.Dispose() }
    return [pscustomobject]@{ Path = $full; Value = $value; Sha256 = $digest; Base64 = [Convert]::ToBase64String($bytes) }
}
function Write-TvJson([string]$Path, $Value, [switch]$Create) {
    $full = Get-TvJsonPath $Path
    $parent = [IO.Path]::GetDirectoryName($full)
    if (!(Test-Path -LiteralPath $parent)) { [void][IO.Directory]::CreateDirectory($parent) }
    $temporary = $full + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
    try {
        [IO.File]::WriteAllText($temporary, (($Value | ConvertTo-Json -Depth 40) + "`n"), [Text.UTF8Encoding]::new($false))
        if ($Create) { [IO.File]::Move($temporary, $full) }
        else { [IO.File]::Replace($temporary, $full, [System.Management.Automation.Language.NullString]::Value) }
    } finally { if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) } }
}
function Test-TvEqual($Left, $Right) {
    return (($Left | ConvertTo-Json -Depth 40 -Compress) -ceq ($Right | ConvertTo-Json -Depth 40 -Compress))
}
function Get-TvTime($Value) {
    if ($Value -is [DateTime] -or $Value -is [DateTimeOffset]) { return [DateTimeOffset]$Value }
    return [DateTimeOffset]::Parse([string]$Value, [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::RoundtripKind)
}
function Resolve-TvTool([string]$Name) {
    $command = Get-Command $Name -ErrorAction Stop | Select-Object -First 1
    Assert-TvCondition ($command.CommandType -in @('Application', 'ExternalScript')) 'tool_must_be_local_file'
    $file = Get-Item -LiteralPath $command.Source -ErrorAction Stop
    Assert-TvCondition (!$file.PSIsContainer -and !($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) 'tool_must_be_local_file'
    return $file.FullName
}
function ConvertTo-TvNativeArgument([string]$Value) {
    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') { return $Value }
    $builder = [Text.StringBuilder]::new(); [void]$builder.Append('"'); $slashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') { $slashes++; continue }
        if ($character -eq '"') { [void]$builder.Append(('\' * ($slashes * 2 + 1))); [void]$builder.Append('"') }
        else { [void]$builder.Append(('\' * $slashes)); [void]$builder.Append($character) }
        $slashes = 0
    }
    [void]$builder.Append(('\' * ($slashes * 2))); [void]$builder.Append('"')
    return $builder.ToString()
}
function Invoke-TvTool([string]$Tool, [string[]]$Arguments, [int]$TimeoutSeconds) {
    $executable = $Tool; $argumentsToUse = $Arguments
    if ([IO.Path]::GetExtension($Tool) -in @('.ps1', '.cmd', '.bat')) {
        # Literal PS data is encoded, never interpolated into a shell command.
        $literal = "'" + $Tool.Replace("'", "''") + "'"
        $values = @($Arguments | ForEach-Object { "'" + $_.Replace("'", "''") + "'" })
        $preference = 'Continue'
        if ([IO.Path]::GetExtension($Tool) -ieq '.ps1') { $preference = 'Stop' }
        $program = '$toolArgs=@(' + ($values -join ',') + '); try { $ErrorActionPreference="' + $preference + '"; $global:LASTEXITCODE=0; & ' + $literal + ' @toolArgs; exit $LASTEXITCODE } catch { [Console]::Error.WriteLine($_.Exception.Message); exit 1 }'
        $executable = (Get-Process -Id $PID).Path
        $argumentsToUse = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($program)))
    }
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $executable
    $info.Arguments = (@($argumentsToUse | ForEach-Object { ConvertTo-TvNativeArgument $_ }) -join ' ')
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    if ([IO.Path]::GetFileName($executable) -ieq 'powershell.exe') {
        $info.EnvironmentVariables['PSModulePath'] = (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\Modules') + ';' + (Join-Path $env:ProgramFiles 'WindowsPowerShell\Modules')
    }
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $info
    try {
        Assert-TvCondition ($process.Start()) 'tool_start_failed'
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        if (!$process.WaitForExit($TimeoutSeconds * 1000)) {
            try { $process.Kill() } catch { }
            throw [TimeoutException]::new('tool_timeout:' + [IO.Path]::GetFileName($Tool))
        }
        Assert-TvCondition ($stdout.Wait(2000) -and $stderr.Wait(2000)) 'tool_output_not_closed'
        Assert-TvCondition ($stdout.Result.Length + $stderr.Result.Length -le 4194304) 'tool_output_too_large'
        Assert-TvCondition ($process.ExitCode -eq 0) ('tool_failed:' + [IO.Path]::GetFileName($Tool) + ':' + $process.ExitCode)
        return $stdout.Result.Trim()
    } finally { $process.Dispose() }
}
function Invoke-TvAdb($Context, [string[]]$Arguments, [int]$TimeoutSeconds = 0) {
    if ($TimeoutSeconds -eq 0) { $TimeoutSeconds = $Context.commandTimeoutSeconds }
    return Invoke-TvTool $Context.adbPath (@('-P', [string]$Context.adbServerPort, '-s', $Context.serial) + $Arguments) $TimeoutSeconds
}
function Get-TvTarget($Context) {
    Assert-TvCondition ((Invoke-TvAdb $Context @('get-state')) -ceq 'device') 'device_not_ready'
    $qemu = Invoke-TvAdb $Context @('shell', 'getprop', 'ro.kernel.qemu')
    if ($Context.emulator) { Assert-TvCondition ($qemu -ceq '1') 'target_is_not_emulator' }
    else { Assert-TvCondition ($qemu -in @('', '0')) 'physical_target_is_emulator' }
    $model = Invoke-TvAdb $Context @('shell', 'getprop', 'ro.product.model')
    $fingerprint = Invoke-TvAdb $Context @('shell', 'getprop', 'ro.build.fingerprint')
    Assert-TvCondition ($model -ceq $Context.expectedModel -and $fingerprint -ceq $Context.expectedFingerprint) 'target_identity_mismatch'
    Assert-TvCondition ((Invoke-TvAdb $Context @('shell', 'am', 'get-current-user')) -ceq '0') 'only_user_zero_supported'
    $sdk = Invoke-TvAdb $Context @('shell', 'getprop', 'ro.build.version.sdk')
    Assert-TvCondition ($sdk -cmatch '^[1-9][0-9]{0,2}$') 'invalid_sdk'
    $abi = Invoke-TvAdb $Context @('shell', 'getprop', 'ro.product.cpu.abi')
    Assert-TvCondition ($abi -cmatch '^[A-Za-z0-9_-]{1,32}$') 'invalid_abi'
    return [pscustomobject][ordered]@{ serial = $Context.serial; model = $model; fingerprint = $fingerprint; sdkInt = [int]$sdk; abi = $abi; userId = 0; emulator = [bool]$Context.emulator; adbServerPort = $Context.adbServerPort }
}
function Get-TvProfile([string]$Id) {
    Assert-TvCondition ($Id -cin @('generic-install-only', 'mi-box-s3-android14')) 'unknown_profile'
    $loaded = Read-TvJson (Join-Path $PSScriptRoot ('setup-profiles/' + $Id + '.json')) 65536
    $profile = $loaded.Value
    Assert-TvKeys $profile @('schemaVersion', 'id', 'title', 'match', 'setHomeAllowed', 'packages')
    Assert-TvCondition ($profile.schemaVersion -eq 1 -and $profile.id -ceq $Id -and $profile.setHomeAllowed -is [bool]) 'invalid_profile'
    Assert-TvString $profile.title 160
    Assert-TvCondition ($profile.match.kind -cin @('generic', 'exact')) 'invalid_profile_match'
    if ($profile.match.kind -ceq 'generic') {
        Assert-TvKeys $profile.match @('kind')
        Assert-TvCondition (!$profile.setHomeAllowed -and @($profile.packages).Count -eq 0) 'invalid_generic_profile'
    } else {
        Assert-TvKeys $profile.match @('kind', 'model', 'sdkInt', 'fingerprint')
        Assert-TvString $profile.match.model 128; Assert-TvString $profile.match.fingerprint 512
        Assert-TvCondition ($profile.match.sdkInt -ge 21 -and $profile.match.sdkInt -le 999) 'invalid_profile_sdk'
    }
    Assert-TvCondition (@($profile.packages).Count -le 16) 'profile_too_large'
    $seen = @(); $stockCount = 0
    foreach ($entry in @($profile.packages)) {
        Assert-TvKeys $entry @('packageName', 'title', 'role', 'defaultSelected', 'reason')
        Assert-TvPackageName $entry.packageName; Assert-TvString $entry.title 160; Assert-TvString $entry.reason 1024
        Assert-TvCondition ($entry.packageName -cne $script:TvPackage -and $seen -cnotcontains $entry.packageName -and $entry.role -cin @('stockHome', 'optional') -and $entry.defaultSelected -is [bool] -and !$entry.defaultSelected) 'invalid_profile_package'
        $seen += $entry.packageName
        if ($entry.role -ceq 'stockHome') { $stockCount++ }
    }
    Assert-TvCondition ($stockCount -le 1) 'ambiguous_stock_home_profile'
    return [pscustomobject]@{ value = $profile; sha256 = $loaded.Sha256 }
}
function Assert-TvSelection($Profile, $Target, $Selection) {
    Assert-TvKeys $Selection @('setHome', 'disableStockHome', 'disablePackages')
    Assert-TvCondition ($Selection.setHome -is [bool] -and $Selection.disableStockHome -is [bool] -and @($Selection.disablePackages).Count -le 16) 'invalid_selection'
    if ($Profile.match.kind -ceq 'exact') { Assert-TvCondition ($Profile.match.model -ceq $Target.model -and $Profile.match.fingerprint -ceq $Target.fingerprint -and $Profile.match.sdkInt -eq $Target.sdkInt) 'profile_firmware_mismatch' }
    Assert-TvCondition (!$Selection.setHome -or $Profile.setHomeAllowed) 'profile_forbids_home'
    Assert-TvCondition (!$Selection.disableStockHome -or ($Selection.setHome -and @($Profile.packages | Where-Object role -CEQ 'stockHome').Count -eq 1)) 'stock_home_requires_home_selection'
    $seen = @()
    foreach ($name in @($Selection.disablePackages)) {
        Assert-TvPackageName $name
        Assert-TvCondition ($seen -cnotcontains $name -and @($Profile.packages | Where-Object { $_.role -ceq 'optional' -and $_.packageName -ceq $name }).Count -eq 1) 'package_not_allowed_by_profile'
        $seen += $name
    }
}
function Get-TvSelectedPackages($Profile, $Selection) {
    $entries = @($Profile.packages | Where-Object { $_.role -ceq 'optional' -and @($Selection.disablePackages) -ccontains $_.packageName })
    if ($Selection.disableStockHome) { $entries += @($Profile.packages | Where-Object role -CEQ 'stockHome') }
    return $entries
}
function Get-TvApk([string]$Path, [string]$Hash, [string]$Cert, [string]$Signer, [string]$Aapt, [int]$Timeout) {
    $expectedHash = Get-TvDigest $Hash; $expectedCert = Get-TvDigest $Cert
    $source = Get-Item -LiteralPath $Path -ErrorAction Stop
    Assert-TvCondition (!$source.PSIsContainer -and $source.Extension -ieq '.apk' -and $source.Length -gt 0 -and $source.Length -le 268435456 -and !($source.Attributes -band [IO.FileAttributes]::ReparsePoint)) 'invalid_apk_file'
    $temp = Join-Path ([IO.Path]::GetTempPath()) ('softlumi-tv-setup-' + [guid]::NewGuid().ToString('N'))
    [void][IO.Directory]::CreateDirectory($temp); $copy = Join-Path $temp 'verified.apk'
    try {
        [IO.File]::Copy($source.FullName, $copy)
        Assert-TvCondition ((Get-TvFileHash $copy) -ceq $expectedHash) 'apk_sha256_mismatch'
        $signature = Invoke-TvTool $Signer @('verify', '--verbose', '--print-certs', $copy) $Timeout
        $signers = [regex]::Matches($signature, '(?m)^Signer #[0-9]+ certificate SHA-256 digest:\s*([A-Fa-f0-9:]+)\s*$')
        Assert-TvCondition ($signers.Count -eq 1 -and (Get-TvDigest $signers[0].Groups[1].Value) -ceq $expectedCert) 'apk_signer_mismatch'
        $metadata = Invoke-TvTool $Aapt @('dump', 'badging', $copy) $Timeout
        $package = [regex]::Match($metadata, "(?m)^package: name='([^']+)' versionCode='([0-9]+)'")
        $sdk = [regex]::Match($metadata, "(?m)^sdkVersion:'([0-9]+)'\s*$")
        Assert-TvCondition ($package.Success -and $package.Groups[1].Value -ceq $script:TvPackage -and $package.Groups[2].Value -match '^[1-9][0-9]{0,9}$' -and [long]$package.Groups[2].Value -le 2147483647) 'apk_package_or_version_mismatch'
        Assert-TvCondition ($metadata -match "(?m)^(?:leanback-)?launchable-activity: name='io\.github\.ekkzy\.softlumi\.MainActivity'" -and $sdk.Success) 'apk_launcher_or_sdk_missing'
        return [pscustomobject]@{ file = $copy; temporaryDirectory = $temp; sourcePath = $source.FullName; sha256 = $expectedHash; signingCertSha256 = $expectedCert; versionCode = $package.Groups[2].Value; minSdk = [int]$sdk.Groups[1].Value }
    } catch { if ([IO.File]::Exists($copy)) { [IO.File]::Delete($copy) }; [IO.Directory]::Delete($temp); throw }
}
function Remove-TvApk($Apk) {
    if ($null -ne $Apk) { if ([IO.File]::Exists($Apk.file)) { [IO.File]::Delete($Apk.file) }; if ([IO.Directory]::Exists($Apk.temporaryDirectory)) { [IO.Directory]::Delete($Apk.temporaryDirectory) } }
}
function Get-TvInstalledHash($Context) {
    $paths = Invoke-TvAdb $Context @('shell', 'pm', 'path', '--user', '0', $script:TvPackage)
    $lines = @($paths -split '\r?\n' | Where-Object { $_ })
    Assert-TvCondition ($lines.Count -eq 1 -and $lines[0] -cmatch '^package:(/data/app/[A-Za-z0-9_./~+=-]+/base\.apk)$') 'installed_base_apk_path_unsupported'
    $path = $Matches[1]
    Assert-TvCondition (@($path.Split('/') | Where-Object { $_ -eq '..' }).Count -eq 0) 'unsafe_installed_apk_path'
    $output = Invoke-TvAdb $Context @('shell', 'sha256sum', $path)
    Assert-TvCondition ($output -cmatch '^([A-Fa-f0-9]{64})\s+\*?(.+)$' -and $Matches[2] -ceq $path) 'installed_sha256_not_confirmed'
    return $Matches[1].ToLowerInvariant()
}
function Get-TvPackage($Context, [string]$Name, [switch]$Soft) {
    Assert-TvPackageName $Name
    $listing = Invoke-TvAdb $Context @('shell', 'pm', 'list', 'packages', '--user', '0', $Name)
    $present = @($listing -split '\r?\n' | Where-Object { $_ -ceq ('package:' + $Name) }).Count
    Assert-TvCondition ($present -le 1) 'ambiguous_package_listing'
    if ($present -eq 0) { return [pscustomobject][ordered]@{ installed = $false; versionCode = $null; enabledState = $null; componentState = $null; baseSha256 = $null } }
    $dump = Invoke-TvAdb $Context @('shell', 'dumpsys', 'package', $Name)
    $version = [regex]::Match($dump, '(?m)^\s*versionCode=([0-9]+)\b')
    $user = [regex]::Match($dump, '(?ms)^\s*User 0:([^\r\n]*)(.*?)(?=^\s*User [0-9]+:|\z)')
    Assert-TvCondition ($version.Success -and $user.Success -and $user.Groups[1].Value -match '\binstalled=true\b') 'package_snapshot_unavailable'
    Assert-TvCondition ($user.Groups[1].Value -match '\benabled=([0-4])\b') 'package_enabled_state_unavailable'
    $state = [int]$Matches[1]; $component = $null; $baseHash = $null
    if ($Soft) {
        $component = 0; $overrides = @()
        foreach ($entry in @(@{ name = 'enabledComponents'; value = 1 }, @{ name = 'disabledComponents'; value = 2 })) {
            $section = [regex]::Match($user.Groups[2].Value, ('(?ms)^([ \t]*)' + $entry.name + ':\s*\r?\n((?:(?:\1[ \t]+)[^\r\n]*\r?\n?)*)'))
            if ($section.Success) {
                $items = @($section.Groups[2].Value -split '\r?\n' | ForEach-Object { $_.Trim() })
                if ($items -ccontains ($script:TvPackage + '.MainActivity') -or $items -ccontains '.MainActivity') { $overrides += $entry.value }
            }
        }
        Assert-TvCondition ($overrides.Count -le 1) 'ambiguous_activity_override'
        if ($overrides.Count -eq 1) { $component = $overrides[0] }
        $baseHash = Get-TvInstalledHash $Context
    }
    return [pscustomobject][ordered]@{ installed = $true; versionCode = $version.Groups[1].Value; enabledState = $state; componentState = $component; baseSha256 = $baseHash }
}
function Get-TvHome($Context, [switch]$AllowChooser) {
    $output = Invoke-TvAdb $Context @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    $lines = @($output -split '\r?\n' | Where-Object { $_ -match '^[A-Za-z][A-Za-z0-9_.]*/\.?[A-Za-z_][A-Za-z0-9_.]*$' })
    Assert-TvCondition ($lines.Count -eq 1) 'home_resolution_ambiguous'
    return Get-TvComponent $lines[0] -AllowChooser:$AllowChooser
}
function Get-TvProtected($Context) {
    return [pscustomobject][ordered]@{
        accessibilityServices = (Invoke-TvAdb $Context @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services'))
        accessibilityEnabled = (Invoke-TvAdb $Context @('shell', 'settings', 'get', 'secure', 'accessibility_enabled'))
        screenOffTimeout = (Invoke-TvAdb $Context @('shell', 'settings', 'get', 'system', 'screen_off_timeout'))
        sleepTimeout = (Invoke-TvAdb $Context @('shell', 'settings', 'get', 'secure', 'sleep_timeout'))
    }
}
function Assert-TvForeground($Context) {
    $window = Invoke-TvAdb $Context @('shell', 'dumpsys', '-t', '8', 'window')
    $focus = [regex]::Match($window, '(?m)^\s*mCurrentFocus=Window\{[^\r\n]*\s(io\.github\.ekkzy\.softlumi/[^\s}]+)[^\r\n]*\}')
    Assert-TvCondition ($focus.Success -and (Get-TvComponent $focus.Groups[1].Value) -ceq $script:TvComponent) 'softlumi_foreground_not_confirmed'
}
function Assert-TvPackageSnapshot($State, [switch]$Soft) {
    Assert-TvKeys $State @('installed', 'versionCode', 'enabledState', 'componentState', 'baseSha256')
    Assert-TvCondition ($State.installed -is [bool]) 'invalid_package_snapshot'
    if (!$State.installed) {
        Assert-TvCondition ($null -eq $State.versionCode -and $null -eq $State.enabledState -and $null -eq $State.componentState -and $null -eq $State.baseSha256) 'invalid_absent_package_snapshot'
        return
    }
    Assert-TvCondition ($State.versionCode -is [string] -and $State.versionCode -cmatch '^[1-9][0-9]{0,9}$' -and $State.enabledState -in @(0, 1, 2, 3, 4)) 'invalid_package_snapshot'
    if ($Soft) {
        Assert-TvCondition ($State.componentState -in @(0, 1) -and $State.enabledState -in @(0, 1)) 'disabled_softlumi_requires_manual_resolution'
        [void](Get-TvDigest $State.baseSha256)
    } else { Assert-TvCondition ($null -eq $State.componentState -and $null -eq $State.baseSha256) 'invalid_profile_package_snapshot' }
}
function Assert-TvPlan($Plan, $Context, [switch]$Fresh) {
    Assert-TvKeys $Plan @('schemaVersion', 'tool', 'transactionId', 'createdUtc', 'expiresUtc', 'target', 'profile', 'apk', 'selection', 'before', 'protected', 'tools')
    Assert-TvCondition ($Plan.schemaVersion -eq 1 -and $Plan.tool -ceq $script:TvToolName -and $Plan.transactionId -cmatch '^[a-f0-9]{32}$') 'unsupported_plan'
    $created = Get-TvTime $Plan.createdUtc; $expires = Get-TvTime $Plan.expiresUtc
    Assert-TvCondition (($expires - $created).TotalSeconds -eq 1800 -and $created -le [DateTimeOffset]::UtcNow.AddMinutes(2)) 'invalid_plan_time'
    if ($Fresh) { Assert-TvCondition ([DateTimeOffset]::UtcNow -le $expires) 'plan_expired_create_new_preview' }
    Assert-TvKeys $Plan.target @('serial', 'model', 'fingerprint', 'sdkInt', 'abi', 'userId', 'emulator', 'adbServerPort')
    Assert-TvCondition ($Plan.target.serial -ceq $Context.serial -and $Plan.target.model -ceq $Context.expectedModel -and $Plan.target.fingerprint -ceq $Context.expectedFingerprint -and $Plan.target.emulator -is [bool] -and $Plan.target.emulator -eq $Context.emulator -and $Plan.target.userId -eq 0 -and $Plan.target.adbServerPort -eq $Context.adbServerPort) 'plan_target_mismatch'
    Assert-TvKeys $Plan.profile @('id', 'sha256')
    $loadedProfile = Get-TvProfile $Plan.profile.id
    Assert-TvCondition ($loadedProfile.sha256 -ceq $Plan.profile.sha256) 'profile_changed_create_new_preview'
    Assert-TvSelection $loadedProfile.value $Plan.target $Plan.selection
    Assert-TvKeys $Plan.apk @('sourcePath', 'sha256', 'signingCertSha256', 'versionCode', 'minSdk')
    Assert-TvString $Plan.apk.sourcePath 1024
    Assert-TvCondition ([IO.Path]::IsPathRooted($Plan.apk.sourcePath) -and !$Plan.apk.sourcePath.StartsWith('\\') -and [IO.Path]::GetExtension($Plan.apk.sourcePath) -ieq '.apk') 'invalid_plan_apk_path'
    [void](Get-TvDigest $Plan.apk.sha256); [void](Get-TvDigest $Plan.apk.signingCertSha256)
    Assert-TvCondition ($Plan.apk.versionCode -is [string] -and $Plan.apk.versionCode -cmatch '^[1-9][0-9]{0,9}$' -and [long]$Plan.apk.versionCode -le 2147483647 -and $Plan.apk.minSdk -ge 1 -and $Plan.apk.minSdk -le $Plan.target.sdkInt) 'invalid_plan_apk_metadata'
    Assert-TvKeys $Plan.before @('soft', 'homeComponent', 'homePackage', 'packages')
    Assert-TvPackageSnapshot $Plan.before.soft -Soft
    [void](Get-TvComponent $Plan.before.homeComponent)
    Assert-TvKeys $Plan.before.homePackage @('packageName', 'state')
    Assert-TvCondition ($Plan.before.homePackage.packageName -ceq $Plan.before.homeComponent.Split('/')[0]) 'invalid_home_snapshot'
    Assert-TvPackageSnapshot $Plan.before.homePackage.state
    Assert-TvCondition ($Plan.before.homePackage.state.installed -and $Plan.before.homePackage.state.enabledState -in @(0, 1)) 'old_home_unavailable'
    $selected = @(Get-TvSelectedPackages $loadedProfile.value $Plan.selection)
    Assert-TvCondition (@($Plan.before.packages).Count -eq $selected.Count) 'invalid_package_snapshot_count'
    for ($i = 0; $i -lt $selected.Count; $i++) {
        $entry = $Plan.before.packages[$i]
        Assert-TvKeys $entry @('packageName', 'state')
        Assert-TvCondition ($entry.packageName -ceq $selected[$i].packageName) 'invalid_package_snapshot_order'
        Assert-TvPackageSnapshot $entry.state
        Assert-TvCondition ($entry.state.installed) 'selected_package_missing'
    }
    Assert-TvCondition ($Plan.selection.setHome -or @($Plan.before.packages | Where-Object { $_.packageName -ceq $Plan.before.homePackage.packageName }).Count -eq 0) 'disabling_current_home_requires_set_home'
    Assert-TvKeys $Plan.protected @('accessibilityServices', 'accessibilityEnabled', 'screenOffTimeout', 'sleepTimeout')
    foreach ($property in $Plan.protected.PSObject.Properties) {
        Assert-TvCondition ($property.Value -is [string] -and $property.Value.Length -le 16384 -and $property.Value -notmatch '[\x00-\x1f\x7f]') 'invalid_protected_snapshot'
    }
    Assert-TvKeys $Plan.tools @('adbPath', 'adbSha256', 'apkSignerPath', 'apkSignerSha256', 'aaptPath', 'aaptSha256')
    foreach ($name in @('adb', 'apkSigner', 'aapt')) {
        Assert-TvString $Plan.tools.($name + 'Path') 1024
        [void](Get-TvDigest $Plan.tools.($name + 'Sha256'))
    }
    return $loadedProfile.value
}
function Get-TvBefore($Context, $Profile, $Selection) {
    $soft = Get-TvPackage $Context $script:TvPackage -Soft
    if ($soft.installed) { Assert-TvPackageSnapshot $soft -Soft }
    $resolvedHome = Get-TvHome $Context
    $homePackage = [pscustomobject][ordered]@{ packageName = $resolvedHome.Split('/')[0]; state = (Get-TvPackage $Context $resolvedHome.Split('/')[0]) }
    Assert-TvCondition ($homePackage.state.installed -and $homePackage.state.enabledState -in @(0, 1)) 'old_home_unavailable'
    $packages = @()
    foreach ($entry in @(Get-TvSelectedPackages $Profile $Selection)) {
        $snapshot = Get-TvPackage $Context $entry.packageName
        Assert-TvCondition ($snapshot.installed) ('selected_package_missing:' + $entry.packageName)
        $packages += [pscustomobject][ordered]@{ packageName = $entry.packageName; state = $snapshot }
    }
    return [pscustomobject][ordered]@{ soft = $soft; homeComponent = $resolvedHome; homePackage = $homePackage; packages = @($packages) }
}
function New-TvPlan($Context, $Options) {
    $path = Get-TvJsonPath $Options.PlanPath
    Assert-TvCondition (!(Test-Path -LiteralPath $path)) 'plan_path_already_exists'
    $profile = Get-TvProfile $Options.ProfileId
    $signer = Resolve-TvTool $Options.ApkSignerPath; $aapt = Resolve-TvTool $Options.AaptPath
    $apk = $null
    try {
        $apk = Get-TvApk $Options.Apk $Options.ApkSha256 $Options.SigningCertSha256 $signer $aapt $Context.commandTimeoutSeconds
        $target = Get-TvTarget $Context
        Assert-TvCondition ($target.sdkInt -ge $apk.minSdk) 'apk_requires_newer_android'
        $selection = [pscustomobject][ordered]@{ setHome = [bool]$Options.SetHome; disableStockHome = [bool]$Options.DisableStockHome; disablePackages = @($Options.DisablePackages) }
        Assert-TvSelection $profile.value $target $selection
        $before = Get-TvBefore $Context $profile.value $selection
        Assert-TvCondition (!$before.soft.installed -or [long]$before.soft.versionCode -le [long]$apk.versionCode) 'downgrade_refused'
        $protected = Get-TvProtected $Context
        $now = [DateTimeOffset]::UtcNow
        $plan = [pscustomobject][ordered]@{
            schemaVersion = 1; tool = $script:TvToolName; transactionId = [guid]::NewGuid().ToString('N')
            createdUtc = $now.ToString('o'); expiresUtc = $now.AddMinutes(30).ToString('o'); target = $target
            profile = [pscustomobject][ordered]@{ id = $profile.value.id; sha256 = $profile.sha256 }
            apk = [pscustomobject][ordered]@{ sourcePath = $apk.sourcePath; sha256 = $apk.sha256; signingCertSha256 = $apk.signingCertSha256; versionCode = $apk.versionCode; minSdk = $apk.minSdk }
            selection = $selection; before = $before; protected = $protected
            tools = [pscustomobject][ordered]@{ adbPath = $Context.adbPath; adbSha256 = (Get-TvFileHash $Context.adbPath); apkSignerPath = $signer; apkSignerSha256 = (Get-TvFileHash $signer); aaptPath = $aapt; aaptSha256 = (Get-TvFileHash $aapt) }
        }
        [void](Assert-TvPlan $plan $Context -Fresh)
        Write-TvJson $path $plan -Create
        Write-Output ('Plan saved: ' + $path)
        Write-Output ('Profile: ' + $profile.value.id + '; SetHome=' + $selection.setHome + '; DisableStockHome=' + $selection.disableStockHome)
        foreach ($entry in @($profile.value.packages)) {
            $selected = ($entry.role -ceq 'stockHome' -and $selection.disableStockHome) -or ($entry.role -ceq 'optional' -and @($selection.disablePackages) -ccontains $entry.packageName)
            Write-Output ('Option ' + $entry.packageName + '; selected=' + $selected + '; ' + $entry.reason)
        }
        foreach ($entry in @($before.packages)) {
            $desired = 3
            if ($entry.state.enabledState -in @(2, 3, 4)) { $desired = $entry.state.enabledState }
            Write-Output ('Selected package ' + $entry.packageName + ': exact enabled state ' + $entry.state.enabledState + ' -> ' + $desired)
            if ($entry.state.enabledState -in @(2, 3, 4)) { Write-Output 'Already disabled or disabled-until-used: exact state retained without a package mutation.' }
        }
        Write-Output ('ApprovedPlanSha256=' + (Get-TvFileHash $path))
        Write-Output 'READ-ONLY PLAN: no device mutation. Approval expires after 30 minutes; review the private JSON before Apply.'
    } finally { Remove-TvApk $apk }
}
function New-TvActions($Plan) {
    $actions = @()
    $actions += [pscustomobject][ordered]@{ id = 'install'; kind = 'Install'; packageName = $script:TvPackage; before = $Plan.before.soft; desired = [pscustomobject][ordered]@{ sha256 = $Plan.apk.sha256; versionCode = $Plan.apk.versionCode }; status = 'Prepared'; observedAfter = $null; error = $null; updatedUtc = $null }
    $actions += [pscustomobject][ordered]@{ id = 'launch'; kind = 'Launch'; packageName = $script:TvPackage; before = $null; desired = $script:TvComponent; status = 'Prepared'; observedAfter = $null; error = $null; updatedUtc = $null }
    foreach ($entry in @($Plan.before.packages)) {
        $desired = 3
        if ($entry.state.enabledState -in @(2, 3, 4)) { $desired = $entry.state.enabledState }
        $actions += [pscustomobject][ordered]@{ id = ('disable:' + $entry.packageName); kind = 'Disable'; packageName = $entry.packageName; before = $entry.state; desired = $desired; status = 'Prepared'; observedAfter = $null; error = $null; updatedUtc = $null }
    }
    if ($Plan.selection.setHome) {
        $actions += [pscustomobject][ordered]@{ id = 'home'; kind = 'Home'; packageName = $script:TvPackage; before = $Plan.before.homeComponent; desired = $script:TvComponent; status = 'Prepared'; observedAfter = $null; error = $null; updatedUtc = $null }
    }
    return $actions
}
function Save-TvJournal([string]$Path, $Journal) {
    $Journal.updatedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    Write-TvJson $Path $Journal
}
function Read-TvJournal([string]$Path, $Context) {
    $journal = (Read-TvJson $Path).Value
    Assert-TvKeys $journal @('schemaVersion', 'tool', 'transactionId', 'createdUtc', 'updatedUtc', 'planSha256', 'planJson', 'plan', 'status', 'actions', 'lastError', 'ownedHomeFallback')
    Assert-TvCondition ($journal.schemaVersion -eq 1 -and $journal.tool -ceq $script:TvToolName -and $journal.status -cin @('Prepared', 'Applying', 'NeedsReconcile', 'Applied', 'Restoring', 'Restored')) 'unsupported_journal'
    Assert-TvCondition ($journal.planJson -is [string] -and $journal.planJson.Length -le 524288) 'invalid_journal_plan'
    $raw = [Convert]::FromBase64String($journal.planJson)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try { $digest = ([BitConverter]::ToString($algorithm.ComputeHash($raw))).Replace('-', '').ToLowerInvariant() }
    finally { $algorithm.Dispose() }
    Assert-TvCondition ($digest -ceq (Get-TvDigest $journal.planSha256)) 'journal_plan_hash_mismatch'
    $decoded = ConvertFrom-Json -InputObject ([Text.UTF8Encoding]::new($false, $true).GetString($raw).TrimStart([char]0xfeff))
    # PS7 materializes ISO dates and writes the local offset; PS5 keeps their
    # strings. Compare the same instants without changing approved source bytes.
    foreach ($field in @('createdUtc', 'expiresUtc')) {
        $decoded.$field = (Get-TvTime $decoded.$field).ToUniversalTime().ToString('o')
        $journal.plan.$field = (Get-TvTime $journal.plan.$field).ToUniversalTime().ToString('o')
    }
    Assert-TvCondition (Test-TvEqual $decoded $journal.plan) 'journal_plan_changed'
    [void](Assert-TvPlan $journal.plan $Context)
    if ($null -ne $journal.ownedHomeFallback) {
        [void](Get-TvComponent $journal.ownedHomeFallback -AllowChooser)
        Assert-TvCondition ($journal.plan.selection.setHome -and @($journal.plan.before.packages | Where-Object { $_.packageName -ceq $journal.plan.before.homePackage.packageName -and $_.state.enabledState -in @(0, 1) }).Count -eq 1) 'unowned_home_fallback'
    }
    Assert-TvCondition ($journal.transactionId -ceq $journal.plan.transactionId) 'journal_transaction_mismatch'
    $age = ([DateTimeOffset]::UtcNow - (Get-TvTime $journal.createdUtc)).TotalDays
    Assert-TvCondition ($age -ge (-2.0 / 1440)) 'journal_future_timestamp'
    $expected = @(New-TvActions $journal.plan)
    Assert-TvCondition (@($journal.actions).Count -eq $expected.Count) 'journal_action_count_changed'
    for ($i = 0; $i -lt $expected.Count; $i++) {
        $action = $journal.actions[$i]; $template = $expected[$i]
        Assert-TvKeys $action @('id', 'kind', 'packageName', 'before', 'desired', 'status', 'observedAfter', 'error', 'updatedUtc')
        foreach ($field in @('id', 'kind', 'packageName', 'before', 'desired')) { Assert-TvCondition (Test-TvEqual $action.$field $template.$field) 'journal_action_changed' }
        Assert-TvCondition ($action.status -cin @('Prepared', 'Running', 'Applied', 'NoOp', 'NeedsReconcile', 'Restoring', 'Restored')) 'invalid_action_status'
    }
    return $journal
}
function Assert-TvLiveBinding($Context, $Plan) {
    Assert-TvCondition (Test-TvEqual (Get-TvTarget $Context) $Plan.target) 'target_changed_since_preview'
    Assert-TvCondition ((Get-TvFileHash $Context.adbPath) -ceq $Plan.tools.adbSha256) 'adb_tool_changed_since_preview'
}
function Assert-TvBeforeApply($Context, $Plan, $Profile) {
    Assert-TvLiveBinding $Context $Plan
    Assert-TvCondition (Test-TvEqual (Get-TvBefore $Context $Profile $Plan.selection) $Plan.before) 'state_changed_since_preview'
    Assert-TvCondition (Test-TvEqual (Get-TvProtected $Context) $Plan.protected) 'protected_state_changed_since_preview'
}
function Set-TvActionRunning([string]$Path, $Journal, $Action, [switch]$Restoring) {
    $Journal.status = 'Applying'; $Action.status = 'Running'
    if ($Restoring) { $Journal.status = 'Restoring'; $Action.status = 'Restoring' }
    $Action.error = $null; $Action.updatedUtc = [DateTimeOffset]::UtcNow.ToString('o')
    Save-TvJournal $Path $Journal
}
function Set-TvActionFailed([string]$Path, $Journal, $Action, [string]$ErrorCode) {
    $Action.status = 'NeedsReconcile'; $Action.error = $ErrorCode
    $Journal.status = 'NeedsReconcile'; $Journal.lastError = $ErrorCode
    Save-TvJournal $Path $Journal
}
function Assert-TvSoftInstalled($Context, $Plan) {
    $soft = Get-TvPackage $Context $script:TvPackage -Soft
    Assert-TvCondition ($soft.installed -and $soft.versionCode -ceq $Plan.apk.versionCode -and $soft.baseSha256 -ceq $Plan.apk.sha256) 'installed_apk_not_confirmed'
    Assert-TvPackageSnapshot $soft -Soft
    if ($Plan.before.soft.installed) { Assert-TvCondition ($soft.enabledState -eq $Plan.before.soft.enabledState -and $soft.componentState -eq $Plan.before.soft.componentState) 'softlumi_override_changed_during_install' }
    return $soft
}
function Invoke-TvApply($Context, $Options) {
    $loaded = Read-TvJson $Options.PlanPath
    Assert-TvCondition ($loaded.Sha256 -ceq (Get-TvDigest $Options.ApprovedPlanSha256)) 'approved_plan_hash_mismatch'
    $plan = $loaded.Value; $profile = Assert-TvPlan $plan $Context -Fresh
    $journalPath = Get-TvJsonPath $Options.JournalPath
    Assert-TvCondition (!(Test-Path -LiteralPath $journalPath)) 'journal_path_already_exists_no_automatic_retry'
    $signer = Resolve-TvTool $plan.tools.apkSignerPath; $aapt = Resolve-TvTool $plan.tools.aaptPath
    Assert-TvCondition ((Get-TvFileHash $signer) -ceq $plan.tools.apkSignerSha256 -and (Get-TvFileHash $aapt) -ceq $plan.tools.aaptSha256) 'apk_tool_changed_since_preview'
    $apk = $null; $journal = $null
    try {
        $apk = Get-TvApk $plan.apk.sourcePath $plan.apk.sha256 $plan.apk.signingCertSha256 $signer $aapt $Context.commandTimeoutSeconds
        Assert-TvCondition ($apk.versionCode -ceq $plan.apk.versionCode -and $apk.minSdk -eq $plan.apk.minSdk) 'apk_metadata_changed'
        Assert-TvBeforeApply $Context $plan $profile
        $journal = [pscustomobject][ordered]@{ schemaVersion = 1; tool = $script:TvToolName; transactionId = $plan.transactionId; createdUtc = [DateTimeOffset]::UtcNow.ToString('o'); updatedUtc = [DateTimeOffset]::UtcNow.ToString('o'); planSha256 = $loaded.Sha256; planJson = $loaded.Base64; plan = $plan; status = 'Prepared'; actions = @(New-TvActions $plan); lastError = $null; ownedHomeFallback = $null }
        Write-TvJson $journalPath $journal -Create
        foreach ($action in @($journal.actions)) {
            if ($action.kind -ceq 'Disable' -and $action.before.enabledState -in @(2, 3, 4)) {
                Assert-TvCondition (Test-TvEqual (Get-TvPackage $Context $action.packageName) $action.before) 'package_changed_before_noop'
                $action.status = 'NoOp'; $action.observedAfter = $action.before; Save-TvJournal $journalPath $journal; continue
            }
            if ($action.kind -ceq 'Install') { Assert-TvCondition (Test-TvEqual (Get-TvPackage $Context $script:TvPackage -Soft) $plan.before.soft) 'softlumi_changed_before_install' }
            if ($action.kind -ceq 'Disable') { Assert-TvCondition (Test-TvEqual (Get-TvPackage $Context $action.packageName) $action.before) 'package_changed_before_disable' }
            if ($action.kind -ceq 'Disable' -and $action.packageName -ceq $plan.before.homePackage.packageName) {
                $currentHome = Get-TvHome $Context -AllowChooser
                Assert-TvCondition ($currentHome -ceq $plan.before.homeComponent -or $currentHome -ceq $script:TvComponent) 'home_changed_before_stock_disable'
            }
            if ($action.kind -ceq 'Launch') { [void](Assert-TvSoftInstalled $Context $plan) }
            Set-TvActionRunning $journalPath $journal $action
            try {
                switch -CaseSensitive ($action.kind) {
                    'Install' {
                        $output = Invoke-TvAdb $Context @('install', '-r', '--user', '0', $apk.file) $Context.installTimeoutSeconds
                        Assert-TvCondition ($output -match '(?m)^Success\s*$') 'install_success_not_reported'
                        $action.observedAfter = Assert-TvSoftInstalled $Context $plan
                    }
                    'Launch' {
                        [void](Invoke-TvAdb $Context @('shell', 'am', 'start', '-W', '-n', $script:TvComponent))
                        Assert-TvForeground $Context; $action.observedAfter = $script:TvComponent
                    }
                    'Disable' {
                        [void](Invoke-TvAdb $Context @('shell', 'pm', 'disable-user', '--user', '0', $action.packageName))
                        $after = Get-TvPackage $Context $action.packageName
                        Assert-TvCondition ($after.installed -and $after.versionCode -ceq $action.before.versionCode -and $after.enabledState -eq $action.desired) 'package_disable_not_confirmed'
                        $action.observedAfter = $after
                        if ($action.packageName -ceq $plan.before.homePackage.packageName) { $journal.ownedHomeFallback = Get-TvHome $Context -AllowChooser }
                    }
                    'Home' {
                        [void](Assert-TvSoftInstalled $Context $plan)
                        $resolvedHome = Get-TvHome $Context -AllowChooser
                        Assert-TvCondition ($resolvedHome -ceq $plan.before.homeComponent -or $resolvedHome -ceq $script:TvComponent -or ($null -ne $journal.ownedHomeFallback -and $resolvedHome -ceq $journal.ownedHomeFallback)) 'home_changed_by_another_action'
                        [void](Invoke-TvAdb $Context @('shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $script:TvComponent))
                        Assert-TvCondition ((Get-TvHome $Context) -ceq $script:TvComponent) 'home_selection_not_confirmed'
                        [void](Invoke-TvAdb $Context @('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'))
                        Assert-TvForeground $Context; $action.observedAfter = $script:TvComponent
                    }
                }
                $action.status = 'Applied'; $action.updatedUtc = [DateTimeOffset]::UtcNow.ToString('o'); Save-TvJournal $journalPath $journal
            } catch { Set-TvActionFailed $journalPath $journal $action $_.Exception.Message; throw }
        }
        if (!(Test-TvEqual (Get-TvProtected $Context) $plan.protected)) { $journal.status = 'NeedsReconcile'; $journal.lastError = 'protected_state_changed'; Save-TvJournal $journalPath $journal; throw 'protected_state_changed' }
        Assert-TvCondition ((Get-TvHome $Context) -ceq $(if ($plan.selection.setHome) { $script:TvComponent } else { $plan.before.homeComponent })) 'final_home_drift'
        $journal.status = 'Applied'; Save-TvJournal $journalPath $journal
        Write-Output ('Applied. Keep the private journal: ' + $journalPath)
        Write-Output 'APK/data retained. Remote services and screen/sleep settings were not configured. Test the physical HOME button separately.'
    } catch {
        if ($null -ne $journal) {
            $journal.status = 'NeedsReconcile'; $journal.lastError = $_.Exception.Message
            try { Save-TvJournal $journalPath $journal } catch { Write-Warning 'Journal update failed; retain the previous write-ahead journal.' }
        }
        throw
    } finally { Remove-TvApk $apk }
}
function Get-TvReconciliation($Context, $Journal) {
    $plan = $Journal.plan
    Assert-TvLiveBinding $Context $plan
    $conflicts = @(); $results = @()
    $protected = Get-TvProtected $Context
    $protectedEqual = Test-TvEqual $protected $plan.protected
    if (!$protectedEqual) { $conflicts += 'protected_state_changed' }
    $soft = Get-TvPackage $Context $script:TvPackage -Soft
    $resolvedHome = Get-TvHome $Context -AllowChooser
    $install = $Journal.actions[0]
    $installUnknown = $install.status -cin @('Running', 'NeedsReconcile', 'Restoring')
    $expectedSoft = $soft.installed -and $soft.versionCode -ceq $plan.apk.versionCode -and $soft.baseSha256 -ceq $plan.apk.sha256 -and $soft.enabledState -in @(0, 1) -and $soft.componentState -in @(0, 1)
    if ($plan.before.soft.installed -and $expectedSoft) { $expectedSoft = $soft.enabledState -eq $plan.before.soft.enabledState -and $soft.componentState -eq $plan.before.soft.componentState }
    $beforeSoft = Test-TvEqual $soft $plan.before.soft
    $installObserved = 'Conflict'
    if ($expectedSoft) { $installObserved = 'Desired' } elseif ($beforeSoft) { $installObserved = 'Before' }
    if (($install.status -ceq 'Applied' -and !$expectedSoft) -or ($install.status -ceq 'Prepared' -and !$beforeSoft) -or $installObserved -ceq 'Conflict') { $conflicts += 'softlumi_install_state_changed' }
    $results += [pscustomobject]@{ id = 'install'; kind = 'Install'; journalStatus = $install.status; observed = $installObserved; needsRestore = $false }
    $results += [pscustomobject]@{ id = 'launch'; kind = 'Launch'; journalStatus = $Journal.actions[1].status; observed = 'NotReversible'; needsRestore = $false }
    $oldHomeOwned = $false; $oldHomeStillDisabled = $false; $deferredHomeCheck = $false
    foreach ($action in @($Journal.actions | Where-Object kind -CEQ 'Disable')) {
        $current = Get-TvPackage $Context $action.packageName
        $sameBefore = Test-TvEqual $current $action.before
        $sameDesired = $current.installed -and $current.versionCode -ceq $action.before.versionCode -and $current.enabledState -eq $action.desired
        $touched = $action.status -cin @('Running', 'Applied', 'NeedsReconcile', 'Restoring')
        $observed = 'Conflict'
        if ($sameBefore) { $observed = 'Before' } elseif ($sameDesired -and $touched) { $observed = 'Desired' }
        if ($observed -ceq 'Conflict') { $conflicts += ('package_drift:' + $action.packageName) }
        if ($action.packageName -ceq $plan.before.homePackage.packageName -and $touched -and $action.before.enabledState -in @(0, 1)) { $oldHomeOwned = $true; $oldHomeStillDisabled = !$sameBefore -and $sameDesired }
        $results += [pscustomobject]@{ id = $action.id; kind = 'Disable'; journalStatus = $action.status; observed = $observed; needsRestore = ($touched -and !$sameBefore -and $sameDesired) }
    }
    if ($plan.before.homePackage.packageName -cne $script:TvPackage -and @($plan.before.packages | Where-Object { $_.packageName -ceq $plan.before.homePackage.packageName }).Count -eq 0) {
        if (!(Test-TvEqual (Get-TvPackage $Context $plan.before.homePackage.packageName) $plan.before.homePackage.state)) { $conflicts += 'old_home_package_drift' }
    }
    $homeActions = @($Journal.actions | Where-Object kind -CEQ 'Home')
    if ($homeActions.Count -eq 1) {
        $action = $homeActions[0]
        $touched = $action.status -cin @('Running', 'Applied', 'NeedsReconcile', 'Restoring')
        $capturedFallback = $null -ne $Journal.ownedHomeFallback -and $Journal.status -cnotin @('Applied', 'Restored') -and $action.status -cne 'Applied' -and $resolvedHome -ceq $Journal.ownedHomeFallback
        $homeAllowed = $resolvedHome -ceq $plan.before.homeComponent -or (($touched -or $oldHomeOwned) -and $resolvedHome -ceq $script:TvComponent) -or $capturedFallback
        # A lost disable response may hide the resolver chosen by Android. Undo
        # only our package first; do not adopt an unknown HOME as our own.
        $deferredHomeCheck = !$homeAllowed -and $oldHomeOwned -and $oldHomeStillDisabled -and $null -eq $Journal.ownedHomeFallback -and $action.status -ceq 'Prepared'
        if ($deferredHomeCheck) { $homeAllowed = $true }
        if (!$homeAllowed) { $conflicts += 'home_changed_by_another_action' }
        $homeObserved = 'Conflict'
        if ($resolvedHome -ceq $plan.before.homeComponent) { $homeObserved = 'Before' }
        elseif ($homeAllowed) { $homeObserved = 'Desired' }
        $results += [pscustomobject]@{ id = $action.id; kind = 'Home'; journalStatus = $action.status; observed = $homeObserved; needsRestore = ($homeAllowed -and $resolvedHome -cne $plan.before.homeComponent) }
    } elseif ($resolvedHome -cne $plan.before.homeComponent) { $conflicts += 'home_changed_by_another_action' }
    $age = ([DateTimeOffset]::UtcNow - (Get-TvTime $Journal.createdUtc)).TotalDays
    if ($age -gt 14) { $conflicts += 'journal_stale_manual_review_required' }
    return [pscustomobject][ordered]@{
        schemaVersion = 1; journalStatus = $Journal.status; targetVerified = $true
        installOutcomeUnknown = [bool]$installUnknown; protectedStateUnchanged = [bool]$protectedEqual; homeRestoreDeferredUntilOldPackage = [bool]$deferredHomeCheck
        observedSoftlumi = $soft; observedHomeComponent = $resolvedHome; actions = @($results); conflicts = @($conflicts)
        canRestore = (!$installUnknown -and $conflicts.Count -eq 0)
    }
}
function Restore-TvPackageAction($Context, [string]$Path, $Journal, $Action) {
    $current = Get-TvPackage $Context $Action.packageName
    if (Test-TvEqual $current $Action.before) { $Action.status = 'Restored'; $Action.observedAfter = $current; Save-TvJournal $Path $Journal; return }
    Assert-TvCondition ($current.installed -and $current.versionCode -ceq $Action.before.versionCode -and $current.enabledState -eq $Action.desired -and $Action.before.enabledState -in @(0, 1)) 'package_drift_during_restore'
    Set-TvActionRunning $Path $Journal $Action -Restoring
    try {
        $command = @('default-state', 'enable', 'disable', 'disable-user', 'disable-until-used')[[int]$Action.before.enabledState]
        [void](Invoke-TvAdb $Context @('shell', 'pm', $command, '--user', '0', $Action.packageName))
        $after = Get-TvPackage $Context $Action.packageName
        Assert-TvCondition (Test-TvEqual $after $Action.before) 'package_restore_not_confirmed'
        $Action.status = 'Restored'; $Action.observedAfter = $after; Save-TvJournal $Path $Journal
    } catch { Set-TvActionFailed $Path $Journal $Action $_.Exception.Message; throw }
}
function Invoke-TvRestore($Context, $Options, $Journal) {
    $path = Get-TvJsonPath $Options.JournalPath
    $inspection = Get-TvReconciliation $Context $Journal
    Assert-TvCondition (!$inspection.installOutcomeUnknown) 'install_outcome_unknown_no_automatic_retry'
    Assert-TvCondition ($inspection.canRestore) ('restore_conflict:' + ($inspection.conflicts -join ','))
    $plan = $Journal.plan
    $Journal.status = 'Restoring'; Save-TvJournal $path $Journal
    try {
        $packageActions = @($Journal.actions | Where-Object kind -CEQ 'Disable')
        $homePackageActions = @($packageActions | Where-Object { $_.packageName -ceq $plan.before.homePackage.packageName })
        # The old HOME package must be available before selecting its activity.
        foreach ($action in $homePackageActions) { Restore-TvPackageAction $Context $path $Journal $action }
        $homeActions = @($Journal.actions | Where-Object kind -CEQ 'Home')
        if ($homeActions.Count -eq 1) {
            $action = $homeActions[0]; $current = Get-TvHome $Context -AllowChooser
            if ($current -cne $plan.before.homeComponent) {
                Assert-TvCondition ($current -ceq $script:TvComponent -or ($null -ne $Journal.ownedHomeFallback -and $current -ceq $Journal.ownedHomeFallback)) 'old_home_package_restored_home_conflict_requires_manual_review'
                Set-TvActionRunning $path $Journal $action -Restoring
                try {
                    [void](Invoke-TvAdb $Context @('shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $plan.before.homeComponent))
                    Assert-TvCondition ((Get-TvHome $Context) -ceq $plan.before.homeComponent) 'home_restore_not_confirmed'
                } catch { Set-TvActionFailed $path $Journal $action $_.Exception.Message; throw }
            }
            $action.status = 'Restored'; $action.observedAfter = $plan.before.homeComponent; Save-TvJournal $path $Journal
        }
        foreach ($action in @($packageActions | Where-Object { $_.packageName -cne $plan.before.homePackage.packageName })) { Restore-TvPackageAction $Context $path $Journal $action }
        Assert-TvCondition (Test-TvEqual (Get-TvProtected $Context) $plan.protected) 'protected_state_changed_during_restore'
        Assert-TvCondition ((Get-TvHome $Context) -ceq $plan.before.homeComponent) 'final_home_restore_not_confirmed'
        $Journal.status = 'Restored'; $Journal.lastError = $null; Save-TvJournal $path $Journal
        Write-Output 'Configuration restored exactly. No APK was downgraded or uninstalled; previous APK bytes and app data were not restored.'
    } catch {
        $Journal.status = 'NeedsReconcile'; $Journal.lastError = $_.Exception.Message
        try { Save-TvJournal $path $Journal } catch { Write-Warning 'Journal update failed; retain the previous write-ahead journal.' }
        throw
    }
}
function Invoke-TvSetup([string]$Mode, [hashtable]$Options) {
    Assert-TvString $Options.Serial 256; Assert-TvString $Options.ExpectedModel 128; Assert-TvString $Options.ExpectedFingerprint 512
    Assert-TvCondition ($Options.Serial -cmatch '^[A-Za-z0-9][A-Za-z0-9._:\[\]-]*$') 'explicit_safe_serial_required'
    if (!$Options.Emulator) { Assert-TvCondition ($Options.Serial -notmatch '^emulator-') 'emulator_switch_required' }
    $context = [pscustomobject]@{ serial = $Options.Serial; expectedModel = $Options.ExpectedModel; expectedFingerprint = $Options.ExpectedFingerprint; emulator = [bool]$Options.Emulator; adbPath = (Resolve-TvTool $Options.AdbPath); adbServerPort = [int]$Options.AdbServerPort; commandTimeoutSeconds = [int]$Options.CommandTimeoutSeconds; installTimeoutSeconds = [int]$Options.InstallTimeoutSeconds }
    Assert-TvCondition ($context.adbServerPort -ge 1024 -and $context.adbServerPort -le 65535 -and $context.commandTimeoutSeconds -ge 1 -and $context.commandTimeoutSeconds -le 120 -and $context.installTimeoutSeconds -ge 1 -and $context.installTimeoutSeconds -le 300) 'invalid_transport_bounds'
    $mutex = $null; $owned = $false
    try {
        if ($Mode -in @('Apply', 'Restore')) {
            $hash = [Security.Cryptography.SHA256]::Create()
            try { $key = ([BitConverter]::ToString($hash.ComputeHash([Text.Encoding]::UTF8.GetBytes($context.serial + ':' + $context.adbServerPort)))).Replace('-', '') }
            finally { $hash.Dispose() }
            $mutex = [Threading.Mutex]::new($false, ('Local\softlumi-tv-setup-' + $key))
            try { $owned = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $owned = $true }
            Assert-TvCondition $owned 'another_setup_operation_is_running'
        }
        switch ($Mode) {
            'Plan' { New-TvPlan $context $Options }
            'Apply' { Invoke-TvApply $context $Options }
            'Inspect' {
                $journal = Read-TvJournal $Options.JournalPath $context
                Get-TvReconciliation $context $journal | ConvertTo-Json -Depth 30
                Write-Output 'READ-ONLY INSPECTION: journal unchanged. Unknown install outcome never authorizes an automatic retry.'
            }
            'Restore' {
                $journal = Read-TvJournal $Options.JournalPath $context
                Invoke-TvRestore $context $Options $journal
            }
            default { throw 'unsupported_mode' }
        }
    } finally { if ($owned) { $mutex.ReleaseMutex() }; if ($null -ne $mutex) { $mutex.Dispose() } }
}

Export-ModuleMember -Function Invoke-TvSetup
