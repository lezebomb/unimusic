<#
.SYNOPSIS
    Starts the UniMusic UnblockNeteaseMusic matching bridge.

.DESCRIPTION
    Copies `bridge.js` into an UnblockNeteaseMusic checkout and runs it with Yarn PnP so that
    `src/provider/match.js` can resolve its dependencies. Installs the checkout's dependencies on
    demand (corepack yarn install --immutable) when `.pnp.cjs` is missing.

.EXAMPLE
    .\run.ps1 -UnmServerDir D:\unimusic\build\unm-lab\server
#>
[CmdletBinding()]
param(
    [string]$UnmServerDir = (Join-Path $PSScriptRoot '..\..\build\unm-lab\server'),
    [int]$Port = 8900,
    [string]$Tiers = 'pyncmd,bodian|kuwo',
    [string]$Token = '',
    [string]$Bind = '0.0.0.0',
    [int]$TimeoutMs = 15000,
    [string]$Node = ''
)

$ErrorActionPreference = 'Stop'

function Resolve-Node {
    param([string]$Explicit)
    if ($Explicit) { return (Resolve-Path -LiteralPath $Explicit).Path }
    foreach ($candidate in @('D:\nodejs\node.exe', "$env:ProgramFiles\nodejs\node.exe")) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    $command = Get-Command node -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw 'node.exe not found. Pass -Node <path>.'
}

$nodePath = Resolve-Node -Explicit $Node
$unmRoot = (Resolve-Path -LiteralPath $UnmServerDir).Path
$matchModule = Join-Path $unmRoot 'src\provider\match.js'
if (-not (Test-Path -LiteralPath $matchModule)) {
    throw "$unmRoot does not look like an UnblockNeteaseMusic checkout (missing src\provider\match.js)."
}

$pnpFile = Join-Path $unmRoot '.pnp.cjs'
if (-not (Test-Path -LiteralPath $pnpFile)) {
    $corepack = Join-Path (Split-Path -Parent $nodePath) 'corepack.cmd'
    if (-not (Test-Path -LiteralPath $corepack)) {
        throw "Dependencies are not installed and corepack was not found next to $nodePath. Run 'corepack yarn install --immutable' in $unmRoot once."
    }
    Write-Host "[unm-bridge] installing dependencies in $unmRoot (corepack yarn install --immutable)"
    Push-Location $unmRoot
    try {
        & $corepack yarn install --immutable
        if ($LASTEXITCODE -ne 0) { throw "yarn install failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'bridge.js') -Destination (Join-Path $unmRoot 'bridge.js') -Force

$env:UNM_BRIDGE_PORT = "$Port"
$env:UNM_BRIDGE_BIND = $Bind
$env:UNM_BRIDGE_TIMEOUT_MS = "$TimeoutMs"
$env:UNM_BRIDGE_TIERS = $Tiers
if ($Token) { $env:UNM_BRIDGE_TOKEN = $Token } else { Remove-Item Env:\UNM_BRIDGE_TOKEN -ErrorAction SilentlyContinue }

Write-Host "[unm-bridge] starting with $nodePath (tiers $Tiers)"
Push-Location $unmRoot
try {
    & $nodePath --require ./.pnp.cjs bridge.js
    exit $LASTEXITCODE
} finally {
    Pop-Location
}

