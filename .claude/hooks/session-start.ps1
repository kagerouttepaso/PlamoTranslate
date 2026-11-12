# Claude Code の SessionStart フック（.claude/settings.json）。仕組みが効いていないときだけ知らせる。
# 何も問題が無ければ何も出さない（毎回の文脈を増やさない）。

$ErrorActionPreference = 'Stop'
# フックや Git Bash から呼ばれると、コンソールの既定のコードページで git の出力を読み、日本語のパスが化ける。入出力を UTF-8 に固定する
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Text.UTF8Encoding]::new($false)

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$notes = [System.Collections.Generic.List[string]]::new()

# コミットの前の検査（.githooks/pre-commit）は、クローンごとの設定が無いと動かない
$hooksPath = git -C $root config --get core.hooksPath 2>$null
if ($hooksPath -ne '.githooks') {
    $notes.Add('このクローンはコミットの前の検査が効いていない。`git config core.hooksPath .githooks` を実行する（docs/手順/開発環境.md「リポジトリ」）。')
}

# 作業の単位の文書は、作業が終わったら畳んで消す
$wip = Join-Path $root 'docs/作業中'
if (Test-Path -LiteralPath $wip) {
    $left = @(Get-ChildItem -LiteralPath $wip -Recurse -File -Filter *.md | ForEach-Object { $_.Name })
    if ($left.Count -gt 0) {
        $notes.Add("docs/作業中/ に文書が残っている（$($left -join '、')）。その作業が終わっていれば、docs-update スキルの手順で 設計（docs/設計）・判断の記録（docs/判断）・バックログへ畳んで消す。")
    }
}

if ($notes.Count -eq 0) { exit 0 }
@{ hookSpecificOutput = @{ hookEventName = 'SessionStart'; additionalContext = ($notes -join "`n") } } | ConvertTo-Json -Compress -Depth 3
exit 0
