$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$directory = Join-Path $repo 'build/machine-qa-libs'
New-Item -ItemType Directory -Force $directory | Out-Null
$output = Join-Path $directory 'mekanism-github.jar'
if (!(Test-Path -LiteralPath $output)) {
    Invoke-WebRequest 'https://github.com/mekanism/Mekanism/releases/download/v1.20.1-10.4.16.80/Mekanism-1.20.1-10.4.16.80.jar' -OutFile $output
}
# The official GitHub build differs from the Modrinth upload; keep a separate QA coordinate.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$jar = [IO.Compression.ZipFile]::OpenRead($output)
try { if (!$jar.GetEntry('META-INF/mods.toml')) { throw 'Not a Forge mod jar' } }
finally { $jar.Dispose() }
$hash = Get-FileHash -LiteralPath $output -Algorithm SHA256
if ($hash.Hash -ne 'AB0E251B8BDA98973D1F4C05D534B8A328B7D6A48964489A51B3BD0E7F382423') {
    throw 'The official Mekanism QA binary differs from the verified release.'
}
$hash

$destination = Join-Path $repo 'build/machine-qa-client/saves/AIFun-Machine-QA'
if (!(Test-Path -LiteralPath (Join-Path $destination 'level.dat'))) {
    $source = Join-Path $repo 'build/gui-qa-server/world'
    if (!(Test-Path -LiteralPath (Join-Path $source 'level.dat'))) {
        throw 'Run -PguiQa runGameTestServer first to create the isolated seed world.'
    }
    New-Item -ItemType Directory -Force $destination | Out-Null
    Copy-Item -LiteralPath (Join-Path $source 'level.dat') -Destination $destination
    Copy-Item -LiteralPath (Join-Path $source 'region') -Destination $destination -Recurse
}
