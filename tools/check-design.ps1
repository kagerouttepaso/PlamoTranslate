<#
.SYNOPSIS
  設計の点検（docs/設計/アーキテクチャ.md「設計の原則」）。コミットの前とリリースの前に回す。

.DESCRIPTION
  設計の幹のうち、機械で確かめられる約束と、構造が崩れ始めた兆候を見る。
  大きな作り替えを要らなくするために、崩れを小さいうちに見つけるのが目的。
  誤り（終了コード1）：設計の約束に反する
    - 中核（core-pipeline）が Android や端末の部品に依存する
    - SingleRequest（翻訳の契約を通らずにエンジンを使う）を、許した場所の外で使う
    - 翻訳エンジンそのもの（EngineHolder.awaitEngine）を、許した場所の外で受け取る
    - llama.cpp の JNI（LlamaNative）を core-llm-jvm の外で使う
    - ロガー（core-log の Logs）を通さずに logcat へ書く
    - モジュールが、docs/設計/アーキテクチャ.md「層とモジュール」の表に無いモジュールに依存する
  注意（終了コードは変えない）：構造を見直す兆候
    - Kotlin のファイルが大きい（責務が集まり始めた兆候）
    - 直近のコミットで何度も変わったファイル（局所修正が重なっている兆候）
  約束や許す場所を変えるときは、先に docs/設計/ を直し、ここを合わせる。
#>
param(
    # 大きいとみなす行数
    [int]$MaxLines = 500,
    # 直近 $Window 回のコミットのうち $HotCount 回以上変わったファイルを兆候にする
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
    'core-llm-jvm/src/main/kotlin/app/plamotranslate/llm/TranslationEngine.kt', # スレッド数の試走
    'app/src/main/java/app/plamotranslate/LlamaEngineLoader.kt',   # 初回の準備
    'app/src/main/java/app/plamotranslate/EngineBenchmark.kt',     # 速さの計測
    'app/src/debug/java/app/plamotranslate/TranslateBenchActivity.kt' # 翻訳モデルの比べ（デバッグ版）
)
foreach ($hit in Grep @('-E', '\bSingleRequest\b', '--', '*.kt')) {
    $file = ($hit -split ':', 2)[0]
    if (-not ($singleRequestAllowed | Where-Object { $file.StartsWith($_) })) {
        $errors.Add("翻訳の契約を通らずにエンジンを使っている：$hit")
    }
}

# 約束2の続き：エンジンそのものを受け取ってよいのは、ストリームの入口・速さの計測・スレッド数を変える口だけ
# （docs/設計/翻訳コア.md「ストリームを通さない呼び出し」）
$awaitEngineAllowed = @(
    'app/src/main/java/app/plamotranslate/EngineHolder.kt',                       # 定義
    'app/src/main/java/app/plamotranslate/translation/Translations.kt',           # ストリームの入口
    'desktop/src/main/kotlin/app/plamotranslate/desktop/DesktopServices.kt',      # PC版のストリームの入口
    'app/src/main/java/app/plamotranslate/EngineBenchmark.kt',                    # 速さの計測
    'app/src/main/java/app/plamotranslate/screen/ScreenTranslateService.kt'       # DEBUG_CONFIG（デバッグ版のスレッド数）
)
foreach ($hit in Grep @('-E', '\bawaitEngine\b', '--', 'app/*.kt', 'desktop/*.kt')) {
    $file = ($hit -split ':', 2)[0]
    if (-not ($awaitEngineAllowed | Where-Object { $file -eq $_ })) {
        $errors.Add("翻訳エンジンそのものを受け取っている：$hit")
    }
}

# 約束3：llama.cpp の JNI は core-llm-jvm の中だけ
foreach ($hit in Grep @('-E', '\bLlamaNative\b', '--', '*.kt', ':(exclude)core-llm-jvm')) {
    $errors.Add("core-llm-jvm の外で JNI を使っている：$hit")
}

# 約束4：ログはすべてロガー（core-log の Logs）を通す。logcat へ直接書いてよいのは、出力先の実装とネイティブのフォールバックだけ
foreach ($hit in Grep @('-E', 'android\.util\.Log|Log\.[diwev]\(', '--', 'app/src', ':(glob)core-*/src/**', ':(exclude)app/src/main/java/app/plamotranslate/diagnostics/LogcatSink.kt')) {
    $errors.Add("ロガー（Logs）を通さずに logcat へ書いている：$hit")
}
foreach ($hit in Grep @('-E', '__android_log_(print|write|vprint)', '--', '*.cpp', '*.c', '*.h', ':(exclude)third_party')) {
    $file = ($hit -split ':', 2)[0]
    $line = ($hit -split ':', 3)[2]
    # 許すのは plamo_llm.cpp の emit_log のフォールバック（「// logcat へのフォールバック」のコメントを付けた行）だけ
    if ($file -ne 'core-llm/src/main/cpp/plamo_llm.cpp' -or $line -notmatch '// logcat へのフォールバック') {
        $errors.Add("ネイティブのログがロガーを通っていない：$hit")
    }
}

# 約束5：モジュールの依存は docs/設計/アーキテクチャ.md「層とモジュール」の表に書いたものだけ。表を正本にし、ここに依存の一覧を書き写さない
$archLines = @(Get-Content -LiteralPath (Join-Path $root 'docs/設計/アーキテクチャ.md') -Encoding utf8)
$allowedDeps = @{}
$testOnlyDeps = @{}
$inLayers = $false
foreach ($line in $archLines) {
    if ($line -match '^#{1,6} ') { $inLayers = $line -match '^## 層とモジュール$'; continue }
    if ($inLayers -and $line -match '^\| `(?<m>[a-z-]+)` \| (?<deps>.+?) \|$') {
        $testOnlyDeps[$Matches['m']] = @([regex]::Matches($Matches['deps'], '`([a-z-]+)`（[^）]*試験のみ）') | ForEach-Object { $_.Groups[1].Value })
        $allowedDeps[$Matches['m']] = @([regex]::Matches($Matches['deps'], '`([a-z-]+)`') | ForEach-Object { $_.Groups[1].Value })
    }
}
$modules = @(Get-ChildItem -LiteralPath $root -Directory | Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'build.gradle.kts') } | ForEach-Object Name)
if ($allowedDeps.Count -lt 3) {
    $errors.Add('docs/設計/アーキテクチャ.md「層とモジュール」の依存の表を読めない（行の形が変わった）')
} else {
    foreach ($m in $modules) {
        if (-not $allowedDeps.ContainsKey($m)) { $errors.Add("モジュール $m が依存の表に無い（docs/設計/アーキテクチャ.md「層とモジュール」に足す）"); continue }
        $gradle = Get-Content -LiteralPath (Join-Path $root "$m/build.gradle.kts") -Encoding utf8 -Raw
        foreach ($d in [regex]::Matches($gradle, 'project\(\s*"(?::)?(?<d>[a-z-]+)"\s*\)')) {
            $dep = $d.Groups['d'].Value
            if ($dep -in $testOnlyDeps[$m] -and $gradle.Substring(0, $d.Index) -notmatch 'testImplementation\(\s*$') {
                $errors.Add("$m が試験専用の依存を製品で使っている：$dep")
            }
            if ($dep -notin $allowedDeps[$m]) { $errors.Add("$m が表に無いモジュールに依存している：$dep（docs/設計/アーキテクチャ.md「層とモジュール」）") }
        }
    }
}

# 兆候1：大きいファイル
foreach ($f in git -C $root -c core.quotepath=false ls-files '*.kt') {
    # 作業ツリーで消して、まだ index に残っているファイルは飛ばす
    if (-not (Test-Path -LiteralPath (Join-Path $root $f))) { continue }
    $n = (Get-Content -LiteralPath (Join-Path $root $f) -Encoding utf8).Count
    if ($n -gt $MaxLines) { $warnings.Add("$f が $n 行。責務が集まっていないかを見る（分けるなら docs/設計/ の拡張ポイントに沿って）") }
}

# 兆候2：直近のコミットで何度も変わったファイル（テストと文書は除く）
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
