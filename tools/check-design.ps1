<#
.SYNOPSIS
  設計の点検（docs/設計/アーキテクチャ.md「設計の原則」）。コミットの前とリリースの前に回す。

.DESCRIPTION
  設計の幹のうち、機械で確かめられる約束と、構造が崩れ始めた合図を見る。
  大きな作り替えを要らなくするために、崩れを小さいうちに見つけるのが目的。
  誤り（終了コード1）：設計の約束に反する
    - 中核（core-pipeline）が Android や端末の部品に依存する
    - SingleRequest（翻訳の契約を通らずにエンジンを使う）を、許した場所の外で使う
    - llama.cpp の JNI（LlamaNative）を core-llm の外で使う
  注意（終了コードは変えない）：構造を見直す合図
    - Kotlin のファイルが大きい（責務が集まり始めた合図）
    - 直近のコミットで何度も変わったファイル（局所修正が重なっている合図）
  約束や許す場所を変えるときは、先に docs/設計/ を直し、ここを合わせる。
#>
param(
    # 大きいとみなす行数
    [int]$MaxLines = 500,
    # 直近 $Window 回のコミットのうち $HotCount 回以上変わったファイルを合図にする
    [int]$Window = 20,
    [int]$HotCount = 5
)

$ErrorActionPreference = 'Stop'
# フックや Git Bash から呼ばれると、コンソールの既定のコードページで git の出力を読み、日本語のパスが化ける。入出力を UTF-8 に固定する
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Text.UTF8Encoding]::new($false)

$root = Split-Path -Parent $PSScriptRoot
$errors = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()

function Grep([string[]]$args2) {
    $out = git -C $root -c core.quotepath=false grep --untracked -n -I @args2 2>$null
    @($out | Where-Object { $_ })
}

# 約束1：中核は Android にも端末の部品（core-llm・ocr・asr・tts、app）にも依存しない
foreach ($hit in Grep @('-E', '^import (android|androidx|app\.plamotranslate\.(llm\.TranslationEngine|llm\.LlamaNative|ocr|asr|tts|screen|camera|conversation|overlay))', '--', 'core-pipeline')) {
    $errors.Add("中核が外に依存している：$hit")
}

# 約束2：翻訳はすべて TranslationStream を通す。エンジンを直接使ってよいのは、速さの計測と初回の準備だけ
$singleRequestAllowed = @(
    'core-pipeline/',                                              # 定義と単体テスト
    'app/src/main/java/app/plamotranslate/EngineHolder.kt',        # 初回の準備
    'app/src/main/java/app/plamotranslate/EngineBenchmark.kt',     # 速さの計測
    'app/src/debug/java/app/plamotranslate/TranslateBenchActivity.kt' # 翻訳モデルの比べ（デバッグ版）
)
foreach ($hit in Grep @('-E', '\bSingleRequest\b', '--', '*.kt')) {
    $file = ($hit -split ':', 2)[0]
    if (-not ($singleRequestAllowed | Where-Object { $file.StartsWith($_) })) {
        $errors.Add("翻訳の契約を通らずにエンジンを使っている：$hit")
    }
}

# 約束3：llama.cpp の JNI は core-llm の中だけ
foreach ($hit in Grep @('-E', '\bLlamaNative\b', '--', '*.kt', ':(exclude)core-llm')) {
    $errors.Add("core-llm の外で JNI を使っている：$hit")
}

# 合図1：大きいファイル
foreach ($f in git -C $root -c core.quotepath=false ls-files '*.kt') {
    $n = (Get-Content -LiteralPath (Join-Path $root $f) -Encoding utf8).Count
    if ($n -gt $MaxLines) { $warnings.Add("$f が $n 行。責務が集まっていないかを見る（分けるなら docs/設計/ の差し込み口に沿って）") }
}

# 合図2：直近のコミットで何度も変わったファイル（テストと文書は除く）
$counts = @{}
$log = git -C $root -c core.quotepath=false log -n $Window --name-only --format='@@' -- '*.kt' '*.cpp' 2>$null
foreach ($line in $log) {
    if (-not $line -or $line -eq '@@') { continue }
    if ($line -match '/(test|androidTest)/') { continue }
    $counts[$line] = 1 + [int]$counts[$line]
}
foreach ($k in $counts.Keys | Sort-Object { -$counts[$_] }) {
    if ($counts[$k] -ge $HotCount -and (Test-Path -LiteralPath (Join-Path $root $k))) {
        $warnings.Add("$k が直近 $Window コミットのうち $($counts[$k]) 回変わった。局所修正が重なっていないかを見る")
    }
}

foreach ($w in $warnings) { Write-Host "注意：$w" -ForegroundColor Yellow }
foreach ($e in $errors) { Write-Host "誤り：$e" -ForegroundColor Red }
Write-Host ("設計の点検：誤り {0} 件、注意 {1} 件" -f $errors.Count, $warnings.Count)
if ($errors.Count -gt 0) { exit 1 }
