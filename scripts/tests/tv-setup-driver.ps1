#requires -Version 5.1
<# Controlled child-process driver. JSON splatting preserves arrays and literal
paths on Windows PowerShell5.1 as well as PowerShell7; no command text is eval'd. #>
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ScriptPath,
      [Parameter(Mandatory=$true)][string]$ParametersPath)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($env:EVEBOX_TV_SETUP_TEST_MARKER -cne 'isolated-offline-fixture-v1' -or
    !$env:EVEBOX_TV_SETUP_TEST_STATE -or
    !(Test-Path -LiteralPath $env:EVEBOX_TV_SETUP_TEST_STATE -PathType Leaf)) {
    throw 'The controlled setup driver requires an explicit offline fixture.'
}
$expected = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\setup-tv.ps1'))
if ([IO.Path]::GetFullPath($ScriptPath) -cne $expected) { throw 'Only this checkout setup-tv.ps1 may be exercised.' }
$fixtureRoot = [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($env:EVEBOX_TV_SETUP_TEST_STATE))
$parameterFile = [IO.Path]::GetFullPath($ParametersPath)
if (!$parameterFile.StartsWith($fixtureRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Test parameters must be inside the isolated fixture.'
}
$json = Get-Content -LiteralPath $parameterFile -Raw | ConvertFrom-Json
$parameters = @{}
foreach ($property in $json.PSObject.Properties) { $parameters[$property.Name] = $property.Value }
& $expected @parameters
exit $LASTEXITCODE
