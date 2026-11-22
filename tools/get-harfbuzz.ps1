$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$repo = Split-Path $PSScriptRoot -Parent
$version = "12.3.2"
$sha256 = "6F6DB164359A2DA5A84EF826615B448B33E6306067AD829D85D5B0BF936F1BB8"
$directory = Join-Path $repo "third_party/harfbuzz"
$archive = Join-Path $directory "harfbuzz-$version.tar.xz"
New-Item -ItemType Directory -Force $directory | Out-Null
if (!(Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $sha256) {
    $url = "https://github.com/harfbuzz/harfbuzz/releases/download/$version/harfbuzz-$version.tar.xz"
    Invoke-WebRequest $url -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $sha256) {
    throw "HarfBuzz のハッシュが違います: $archive"
}
& "$env:SystemRoot/System32/tar.exe" -xf $archive -C $directory
if ($LASTEXITCODE -ne 0) { throw "HarfBuzz を展開できません: $archive" }
"HarfBuzz: $(Join-Path $directory "harfbuzz-$version")"
