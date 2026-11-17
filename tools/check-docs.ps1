<#
.SYNOPSIS
  docs/ の検査（docs/README.md「区切りでの更新と検査」）。作業の区切りごとに回す。

.DESCRIPTION
  文書の方針が仕組みとして守られているかを、機械で確かめられる範囲で見る。
  誤り（終了コード1）：
    - コード・CLAUDE.md・docs/ から参照している文書のファイルや節（「…」）が無い
    - docs/現在地.md が100行を超える
    - docs/判断/ のファイルの名前や書式が欠けている
    - 使わない言い換え（docs/設計/アーキテクチャ.md の表）が、文書・コード・画面・ファイル名にある
    - docs/現在地.md・docs/バックログ.md に予定・やることの節がある、または個人のフォルダーのパスがある
    - 今の姿を書く文書が `…` で示す識別子（CamelCase・camelCase）が、コードのどこにも無い
  注意（終了コードは変えない）：
    - 今の姿を書く文書（docs/設計/・docs/手順/）に日付がある（経緯が混ざった兆候）
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

# 節の名前：見出しの文字列（# を除く）と、箇条の先頭の太字の名前（CLAUDE.md の規則の名前など）。ファイルごとに1回だけ読む
$headingCache = @{}
function Get-Headings([string]$file) {
    if (-not $headingCache.ContainsKey($file)) {
        $headingCache[$file] = @(Get-Content -LiteralPath $file -Encoding utf8 | ForEach-Object {
                if ($_ -match '^#{1,6} ') { ($_ -replace '^#{1,6} ', '').Trim() }
                elseif ($_ -match '^\s*(?:-|\d+\.) \*\*(?<name>[^*]+)\*\*') { $Matches['name'].Trim() }
            })
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
$pathPattern = '(?<path>(?:docs/)?(?:[^\s`''"「」（）()、。,：\[\]<>:*|]+/)*[^\s`''"「」（）()、。,：\[\]<>:*|/]+\.md)`?(?<secs>(?:「[^」\r\n]+」)*)'
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
        # .md を書かずに節を付けた docs/ の参照（docs/xx「節」）は、ファイルもフォルダーも無ければ参照切れ
        foreach ($m in [regex]::Matches($line, 'docs/(?<p>[^\s`''"「」（）()、。,：\[\]<>:*|]+?)(?<!\.md)「')) {
            $p = $m.Groups['p'].Value
            if (-not (Test-Path -LiteralPath (Join-Path $docs $p)) -and -not (Test-Path -LiteralPath (Join-Path $docs "$p.md"))) {
                $errors.Add("$(Rel $src.FullName):$($i + 1) 参照先の文書が無い：docs/$p")
            }
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
                # docs/ の外の Markdown（リポジトリ直下の CLAUDE.md・README.md など）は、そこにあればその節も確かめる
                $outside = Join-Path $root $f.Path
                if (-not $f.Path.StartsWith('docs/') -and (Test-Path -LiteralPath $outside)) {
                    $target = $outside
                } else {
                    $errors.Add("$where 参照先の文書が無い：$($f.Path)")
                    continue
                }
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
            if ($state -ne '有効' -and -not $state.StartsWith('置き換え') -and -not $state.StartsWith('一部置き換え')) { $errors.Add("$where 状態は「有効」「置き換え（→ …）」「一部置き換え（→ …）」のどれか：$state") }
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

# 公開するリポジトリに置かない記録（CLAUDE.md「公開するリポジトリに置く記録を分ける」）。機械で見分けられる形だけを見る：
# 現在地とバックログの、予定・やることを表す節の見出しと、個人のフォルダーのパス（文書・コードのどこでも）
foreach ($name in '現在地.md', 'バックログ.md') {
    $f = Join-Path $docs $name
    if (-not (Test-Path -LiteralPath $f)) { continue }
    $lines = @(Get-Content -LiteralPath $f -Encoding utf8)
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^#{1,6} .*(次の作業|次にやる|予定|やること|TODO|日誌)') {
            $errors.Add("docs/${name}:$($i + 1) 予定・やることの節は置かない（リポジトリの外の非公開の記録へ）：$($lines[$i])")
        }
    }
}
$privatePath = '[A-Za-z]:[\\/]+Users[\\/]|/home/[a-z]|/Users/[A-Za-z]|Obsidian|Vault'
foreach ($hit in @(git -C $root -c core.quotepath=false grep -n -I -E $privatePath -- . ':(exclude)third_party' ':(exclude)tools/check-docs.ps1' 2>$null | Where-Object { $_ })) {
    $errors.Add("個人のフォルダーのパスを書かない：$hit")
}

# 文書が示す識別子がコードにあるか。部品を説明する文書（設計・現在地・バックログ・要件・CLAUDE.md）の `…` の中の CamelCase・camelCase の語が、
# コード・スクリプト・設定のどこにも無ければ、名前を変えたか消したのに文書を直していない（設計書がコードから離れた兆候）
$codeFiles = @(git -C $root -c core.quotepath=false ls-files -- app 'core-*' featurelab tools gradle .githooks .claude '*.kts' 2>$null |
        Where-Object { $_ -match '\.(kt|kts|java|cpp|h|c|xml|py|ps1|toml|pro|txt|json|sh)$|(^|/)pre-commit$' })
$codeWords = [System.Collections.Generic.HashSet[string]]::new()
foreach ($rel in $codeFiles) {
    $full = Join-Path $root $rel
    if (-not (Test-Path -LiteralPath $full)) { continue }
    foreach ($w in [regex]::Matches([IO.File]::ReadAllText($full), '[A-Za-z_][A-Za-z0-9_]*')) { [void]$codeWords.Add($w.Value) }
}
# このリポジトリのコードには出てこない外の名前（Android・Java・Gradle）。文書が外の名前を新しく示すときだけ足す
foreach ($w in 'installDebug', 'assembleDebug', 'assembleRelease', 'testDebugUnitTest', 'filterTouchesWhenObscured', 'SocketException') { [void]$codeWords.Add($w) }
# ファイルの名前（拡張子を除く）も、文書が部品の名前として示すので含める
foreach ($rel in @(git -C $root -c core.quotepath=false ls-files 2>$null)) { [void]$codeWords.Add([IO.Path]::GetFileNameWithoutExtension($rel)) }
# 見るのは、このアプリの部品を説明する文書だけ。端末の能力（Android の内部の名前）と手順（Gradle のタスクやコマンドの引数）は外の名前が多いので見ない
$nowDocs = @(Get-ChildItem -LiteralPath (Join-Path $docs '設計') -File -Filter *.md) + @('現在地.md', 'バックログ.md', '要件.md' | ForEach-Object { Get-Item -LiteralPath (Join-Path $docs $_) }) + @(Get-Item -LiteralPath (Join-Path $root 'CLAUDE.md'))
foreach ($f in $nowDocs) {
    $lines = @(Get-Content -LiteralPath $f.FullName -Encoding utf8)
    for ($i = 0; $i -lt $lines.Count; $i++) {
        foreach ($code in [regex]::Matches($lines[$i], '`([^`]+)`')) {
            foreach ($tok in [regex]::Matches($code.Groups[1].Value, '\b(?:[A-Z][a-z0-9]+(?:[A-Z][A-Za-z0-9]*)+|[a-z]+[A-Z][A-Za-z0-9]*)\b')) {
                if (-not $codeWords.Contains($tok.Value)) { $errors.Add("$(Rel $f.FullName):$($i + 1) コードに無い識別子：$($tok.Value)（名前が変わったか消えた。文書を今のコードに合わせる）") }
            }
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

# 独自の呼び名（docs/設計/アーキテクチャ.md「使わない言い換え」）。表の「検査の形」を読み、文書・コード・画面・ファイル名に戻っていないかを見る。
# 表を正本にし、語をこのスクリプトに書き写さない（表と検査を二重に持たない）
$glossaryFile = Join-Path $docs '設計/アーキテクチャ.md'
$glossary = @()
$glossaryLines = @(Get-Content -LiteralPath $glossaryFile -Encoding utf8)
$inTable = $false
$tableStart = 0; $tableEnd = 0
for ($i = 0; $i -lt $glossaryLines.Count; $i++) {
    $line = $glossaryLines[$i]
    if ($line -match '^#{1,6} ') {
        if ($inTable) { $tableEnd = $i; break }
        $inTable = $line -match '^#{1,6} 使わない言い換え$'
        if ($inTable) { $tableStart = $i + 1 }
        continue
    }
    # 行の形：| 使わない語 | 使う語 | `検査の形` |（検査の形の中の | は \| と書く）
    if ($inTable -and $line -match '^\| (?<word>.+?) \| (?<use>.+?) \| `(?<pattern>.+)` \|$') {
        $glossary += [pscustomobject]@{ Word = $Matches['word']; Use = $Matches['use']; Pattern = [regex]::new($Matches['pattern'].Replace('\|', '|')) }
    }
}
if ($inTable -and $tableEnd -eq 0) { $tableEnd = $glossaryLines.Count }
if ($glossary.Count -lt 10) {
    $errors.Add('docs/設計/アーキテクチャ.md「使わない言い換え」の表を読めない（行の形が変わった）')
} else {
    $glossaryRel = Rel $glossaryFile
    # 対象：Git が追う（または追い得る）文字のファイル。外のライブラリと、翻訳に渡す文の組・評価の結果（利用者の文のデータで、このリポジトリの書き方ではない）は除く
    $files = git -C $root -c core.quotepath=false ls-files --cached --others --exclude-standard -- . ':(exclude)third_party' ':(exclude)*.tsv' ':(exclude)tools/testdata' ':(exclude)tools/bench-results' 2>$null
    $textExt = '.md', '.kt', '.kts', '.java', '.xml', '.ps1', '.py', '.sh', '.cpp', '.h', '.c', '.pro', '.json', '.properties', '.txt', '.toml', '.yml', '.yaml', '.gitignore', '.gitattributes'
    foreach ($rel in $files) {
        foreach ($g in $glossary) {
            if ($g.Pattern.IsMatch($rel)) { $errors.Add("$rel ファイル名に使わない語「$($g.Word)」→ $($g.Use)") }
        }
        $ext = [IO.Path]::GetExtension($rel)
        if (-not $ext) { $ext = [IO.Path]::GetFileName($rel) }
        if ($ext -notin $textExt -and $rel -notmatch '(^|/)pre-commit$') { continue }
        $full = Join-Path $root $rel
        if (-not (Test-Path -LiteralPath $full)) { continue }
        $lines = @(Get-Content -LiteralPath $full -Encoding utf8)
        for ($i = 0; $i -lt $lines.Count; $i++) {
            # 表そのものは除く
            if ($rel -eq $glossaryRel -and $i -ge $tableStart -and $i -lt $tableEnd) { continue }
            foreach ($g in $glossary) {
                if ($g.Pattern.IsMatch($lines[$i])) { $errors.Add("${rel}:$($i + 1) 使わない語「$($g.Word)」→ $($g.Use)") }
            }
        }
    }
}

foreach ($w in $warnings) { Write-Host "注意：$w" -ForegroundColor Yellow }
foreach ($e in $errors) { Write-Host "誤り：$e" -ForegroundColor Red }
Write-Host ("誤り {0} 件、注意 {1} 件" -f $errors.Count, $warnings.Count)
if ($errors.Count -gt 0) { exit 1 }
