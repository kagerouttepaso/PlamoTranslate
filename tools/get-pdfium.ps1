$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$repo = Split-Path $PSScriptRoot -Parent
$version = "8165"
$sha256 = "CA7338AE7779B8B1B82F4580305C4D762D214F8DF1D54B47647FE3CC75989DED"
$directory = Join-Path $repo "third_party/pdfium"
$archive = Join-Path $directory "pdfium-$version-win-x64.tgz"
$destination = Join-Path $directory "chromium-$version-win-x64"
New-Item -ItemType Directory -Force $directory, $destination | Out-Null
if (!(Test-Path -LiteralPath $archive) -or (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $sha256) {
    $url = "https://github.com/bblanchon/pdfium-binaries/releases/download/chromium/$version/pdfium-win-x64.tgz"
    Invoke-WebRequest $url -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $sha256) {
    throw "PDFium のハッシュが違います: $archive"
}
& "$env:SystemRoot/System32/tar.exe" -xzf $archive -C $destination
if ($LASTEXITCODE -ne 0) { throw "PDFium を展開できません: $archive" }
"PDFium: $destination"
