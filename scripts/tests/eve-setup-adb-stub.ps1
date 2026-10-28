# Discovery/connect double for the wizard. Addressed backend commands delegate
# only to the existing controlled PowerShell double, never to Android tools.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($env:SOFTLUMI_TV_SETUP_TEST_MARKER -cne 'isolated-offline-fixture-v1' -or
    !$env:SOFTLUMI_TV_SETUP_TEST_STATE -or !$env:SOFTLUMI_EVE_SETUP_TEST_DELEGATE) {
    throw 'Explicit isolated EVE tool fixture required.'
}
$statePath = $env:SOFTLUMI_TV_SETUP_TEST_STATE
$fixture = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
$argv = @($args | ForEach-Object { [string]$_ })
if ($argv.Count -lt 3 -or $argv[0] -cne '-P' -or $argv[1] -cne '5037') { throw 'Uncontrolled EVE ADB port or arguments.' }
if ($argv.Count -eq 4 -and $argv[2] -ceq 'devices' -and $argv[3] -ceq '-l') {
    [IO.File]::AppendAllText(($statePath + '.calls'), ((@{Tool='adb'; Args=$argv} | ConvertTo-Json -Compress) + "`n"))
    Write-Output 'List of devices attached'
    foreach ($entry in @($fixture.Devices)) { Write-Output ($entry.serial + "`t" + $entry.state + ' product:controlled model:Controlled_TV transport_id:1') }
    exit 0
}
if ($argv.Count -eq 4 -and $argv[2] -ceq 'connect') {
    [IO.File]::AppendAllText(($statePath + '.calls'), ((@{Tool='adb'; Args=$argv} | ConvertTo-Json -Compress) + "`n"))
    if ($argv[3] -cne $fixture.Serial) { throw 'The fixture rejects connection to an uncontrolled address.' }
    $fixture.Devices = @([pscustomobject]@{serial=$fixture.Serial; state='device'})
    [IO.File]::WriteAllText($statePath, ($fixture | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
    Write-Output ('connected to ' + $fixture.Serial)
    exit 0
}
if ($argv.Count -lt 5 -or $argv[2] -cne '-s') { throw 'Uncontrolled EVE ADB command.' }
$env:SOFTLUMI_TV_SETUP_TEST_ROLE = 'adb'
$delegate = [IO.Path]::GetFullPath($env:SOFTLUMI_EVE_SETUP_TEST_DELEGATE)
$expected = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'tv-setup-tool-stub.ps1'))
if ($delegate -cne $expected) { throw 'The fixture refuses an unexpected ADB delegate.' }
& $delegate @argv
exit $LASTEXITCODE
