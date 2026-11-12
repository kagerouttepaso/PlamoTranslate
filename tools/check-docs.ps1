<#
.SYNOPSIS
  docs/ の検査（docs/README.md「区切りでの更新と検査」）。作業の区切りごとに回す。

.DESCRIPTION
  文書の方針が仕組みとして守られているかを、機械で確かめられる範囲で見る。
  誤り（終了コード1）：
    - コード・CLAUDE.md・docs/ から参照している文書のファイルや節（「…」）が無い
    - docs/現在地.md が100行を超える
    - docs/判断/ のファイルの名前や書式が欠けている
  注意（終了コードは変えない）：
    - 今の姿を書く文書（docs/設計/・docs/手順/）に日付がある（経緯が混ざった合図）
    - docs/作業中/ に文書が残っている（作業が終わったら畳んで消す）
#>
param()

$ErrorActionPreference = 'Stop'
# フックや Git Bash から呼ばれると、コンソールの既定のコードページで git の出力を読み、日本語のパスが化ける。入出力を UTF-8 に固定する
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Text.UTF8Encoding]::new($false)

$root = Split-Path -Parent $PSScriptRoot
$docs = Join-Path $root 'docs'
$errors = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Rel([string]$path) { [IO.Path]::GetRelativePath($root, $path).Replace('\', '/') }

# 見出しの文字列（# を除く）。ファイルごとに1回だけ読む
$headingCache = @{}
function Get-Headings([string]$file) {
    if (-not $headingCache.ContainsKey($file)) {
        $headingCache[$file] = @(Get-Content -LiteralPath $file -Encoding utf8 |
            Where-Object { $_ -match '^#{1,6} ' } |
            ForEach-Object { ($_ -replace '^#{1,6} ', '').Trim() })
    }
    $headingCache[$file]
}

# 参照の解決：docs/ から始まればリポジトリ直下から、そうでなければ参照元のフォルダー、次に docs/ から
function Resolve-Doc([string]$ref, [string]$from) {
    if ($ref.StartsWith('docs/')) { return Join-Path $root $ref }
    $candidates = @((Join-Path (Split-Path -Parent $from) $ref), (Join-Path $docs $ref))
    foreach ($c in $candidates) { if (Test-Path -LiteralPath $c) { return $c } }
    $candidates[0]
}

# 置き場：docs/ の直下に置けるのは決まった文書とフォルダーだけ（docs/README.md「置き場と役割」）
$rootFiles = 'README.md', '現在地.md', 'バックログ.md', '要件.md'
$rootDirs = '設計', '手順', '資料', '判断', '作業中'
foreach ($item in Get-ChildItem -LiteralPath $docs -Force) {
    if ($item.PSIsContainer) {
        if ($item.Name -notin $rootDirs) { $errors.Add("docs/$($item.Name)/ は決まった置き場でない（$($rootDirs -join '・')）") }
    } elseif ($item.Name -notin $rootFiles) {
        $errors.Add("docs/$($item.Name) は直下に置けない。役割に合うフォルダーへ（作業の単位の文書は docs/作業中/）")
    }
}

# 調べる対象：docs/ の文書と CLAUDE.md は全文、コードとスクリプトは docs/ を含む行だけ（git grep で拾う）
$texts = [System.Collections.Generic.List[object]]::new()
$docFiles = @(Get-ChildItem -LiteralPath $docs -Recurse -File -Filter *.md) + @(Get-Item -LiteralPath (Join-Path $root 'CLAUDE.md'))
foreach ($f in $docFiles) {
    $lines = @(Get-Content -LiteralPath $f.FullName -Encoding utf8)
    $inFence = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*```') { $inFence = -not $inFence; continue }
        if (-not $inFence) { $texts.Add([pscustomobject]@{ File = $f.FullName; Line = $i + 1; Text = $lines[$i]; IsDoc = $true }) }
    }
}
$self = Rel $PSCommandPath
$grep = git -C $root -c core.quotepath=false grep --untracked -n -I -e 'docs/' -- . ':(exclude)docs' ':(exclude)CLAUDE.md' ':(exclude)third_party' 2>$null
foreach ($g in $grep) {
    if ($g -notmatch '^(?<file>[^:]+):(?<line>\d+):(?<text>.*)$') { continue }
    if ($Matches['file'] -eq $self) { continue }
    $texts.Add([pscustomobject]@{ File = (Join-Path $root $Matches['file']); Line = [int]$Matches['line']; Text = $Matches['text']; IsDoc = $false })
}

# 参照の形：`docs/…/x.md` か、docs/ の中の相対パス。続く「節」は0個以上
$pathPattern = '(?<path>(?:docs/)?(?:[^\s`''"「」（）()、。,\[\]<>:*|]+/)*[^\s`''"「」（）()、。,\[\]<>:*|/]+\.md)`?(?<secs>(?:「[^」\r\n]+」)*)'
$linkPattern = '\]\((?<path>[^)#\s]+\.md)(?:#[^)]*)?\)'

foreach ($t in $texts) {
    $line = $t.Text
    $isDoc = $t.IsDoc
    $src = [pscustomobject]@{ FullName = $t.File }
    $i = $t.Line - 1
    if ($true) {
        $found = @()
        foreach ($m in [regex]::Matches($line, $pathPattern)) {
            $path = $m.Groups['path'].Value
            # コードの中では docs/ から始まる参照だけを見る（ほかの .md はファイル名の例のことがある）
            if (-not $isDoc -and -not $path.StartsWith('docs/')) { continue }
            # 文書の中で docs/ の外を指すもの（README.md など直下のファイル名だけ）は、docs/ から解決できるときだけ見る
            $found += [pscustomobject]@{ Path = $path; Secs = $m.Groups['secs'].Value }
        }
        if ($isDoc) {
            foreach ($m in [regex]::Matches($line, $linkPattern)) {
                $found += [pscustomobject]@{ Path = $m.Groups['path'].Value; Secs = '' }
            }
        }
        foreach ($f in $found) {
            if ($f.Path -match '^https?:') { continue }
            # 書式の例（YYYY-MM-DD_題.md など）は参照ではない
            if ($f.Path -match 'YYYY|…') { continue }
            $target = Resolve-Doc $f.Path $src.FullName
            $where = "$(Rel $src.FullName):$($i + 1)"
            if (-not (Test-Path -LiteralPath $target)) {
                # docs/ の外の Markdown（リポジトリ直下の README.md など）は、そこにあれば良い
                if (-not $f.Path.StartsWith('docs/') -and (Test-Path -LiteralPath (Join-Path $root $f.Path))) { continue }
                $errors.Add("$where 参照先の文書が無い：$($f.Path)")
                continue
            }
            if ($f.Secs) {
                $heads = Get-Headings $target
                foreach ($s in [regex]::Matches($f.Secs, '「([^」]+)」')) {
                    $name = $s.Groups[1].Value
                    $hit = $heads | Where-Object { $_ -eq $name -or $_.StartsWith($name + '（') -or $_.StartsWith($name + '：') }
                    if (-not $hit) { $errors.Add("$where 参照先の節が無い：$($f.Path)「$name」") }
                }
            }
        }
    }
}

# 現在地は100行以内
$now = Join-Path $docs '現在地.md'
if (Test-Path -LiteralPath $now) {
    $n = (Get-Content -LiteralPath $now -Encoding utf8).Count
    if ($n -gt 100) { $errors.Add("docs/現在地.md が $n 行（100行以内に書き換える）") }
} else {
    $errors.Add('docs/現在地.md が無い')
}

# 判断の記録の名前と書式
$decisions = Join-Path $docs '判断'
if (Test-Path -LiteralPath $decisions) {
    foreach ($f in Get-ChildItem -LiteralPath $decisions -File -Filter *.md) {
        $where = Rel $f.FullName
        if ($f.Name -notmatch '^\d{4}-\d{2}-\d{2}_.+\.md$') { $errors.Add("$where 名前が「YYYY-MM-DD_題.md」でない") }
        $text = Get-Content -LiteralPath $f.FullName -Encoding utf8 -Raw
        foreach ($field in '状態', '問題', '決めたこと', '理由', '再発を防ぐもの') {
            if ($text -notmatch "(?m)^- $field：") { $errors.Add("$where 「- $field：」が無い") }
        }
        if ($text -match '(?m)^- 状態：(?<s>.*)$') {
            $state = $Matches['s'].Trim()
            if ($state -ne '有効' -and -not $state.StartsWith('置き換え')) { $errors.Add("$where 状態は「有効」か「置き換え（→ …）」：$state") }
        }
    }
}

# 今の姿の文書に日付
foreach ($dir in '設計', '手順') {
    $p = Join-Path $docs $dir
    if (-not (Test-Path -LiteralPath $p)) { continue }
    foreach ($f in Get-ChildItem -LiteralPath $p -Recurse -File -Filter *.md) {
        $lines = @(Get-Content -LiteralPath $f.FullName -Encoding utf8)
        $inFence = $false
        $inPerformance = $false
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $line = $lines[$i]
            if ($line -match '^\s*```') { $inFence = -not $inFence; continue }
            # コードブロック（ログやファイル名の例）は見ない
            if ($inFence) { continue }
            # 性能の目安の節では、測った日は数値の条件なので書いてよい（docs/README.md「1つの事実は1か所」）
            if ($line -match '^#{1,6} ') { $inPerformance = $line -match '性能の目安'; continue }
            if ($inPerformance) { continue }
            # 判断の記録へのリンク（ファイル名の日付）、インラインのコード、版の名前（2024-07-17版）は除く
            $plain = $line -replace '判断/\d{4}-\d{2}-\d{2}_', '' -replace '`[^`]*`', '' -replace '\d{4}-\d{2}-\d{2}版', ''
            if ($plain -match '20\d\d-\d\d-\d\d') { $warnings.Add("$(Rel $f.FullName):$($i + 1) 日付がある（経緯なら判断の記録へ）") }
        }
    }
}

# 作業中の残り
$wip = Join-Path $docs '作業中'
if (Test-Path -LiteralPath $wip) {
    foreach ($f in Get-ChildItem -LiteralPath $wip -Recurse -File -Filter *.md) {
        $warnings.Add("$(Rel $f.FullName) 作業中の文書が残っている（終わったら畳んで消す）")
    }
}

foreach ($w in $warnings) { Write-Host "注意：$w" -ForegroundColor Yellow }
foreach ($e in $errors) { Write-Host "誤り：$e" -ForegroundColor Red }
Write-Host ("誤り {0} 件、注意 {1} 件" -f $errors.Count, $warnings.Count)
if ($errors.Count -gt 0) { exit 1 }
