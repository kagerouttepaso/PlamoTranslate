# Claude Code の Stop フック（.claude/settings.json）。AIが手を止める前に、文書の検査と設計の点検を回す。
# 作業ツリーに変更があり、誤りが出たら、止めずに直させる（CLAUDE.md「規則を支える仕組み」）。
# 同じ停止の中で2回目に呼ばれたとき（stop_hook_active）は止めない。直せない誤りで回り続けないため。

$ErrorActionPreference = 'Stop'
# フックや Git Bash から呼ばれると、コンソールの既定のコードページで git の出力を読み、日本語のパスが化ける。入出力を UTF-8 に固定する
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Text.UTF8Encoding]::new($false)

$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

$active = $false
try {
    $payload = [Console]::In.ReadToEnd() | ConvertFrom-Json
    $active = [bool]$payload.stop_hook_active
} catch { }

# 変更が無ければ、前からある誤りで止めない
$changes = git -C $root status --porcelain 2>$null
if (-not $changes) { exit 0 }

$found = [System.Collections.Generic.List[string]]::new()
foreach ($check in @(
        @{ Script = 'tools/check-docs.ps1'; Skill = 'docs-update' },
        @{ Script = 'tools/check-design.ps1'; Skill = 'change-flow' })) {
    $out = & pwsh -NoProfile -File (Join-Path $root $check.Script) 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        $lines = $out -split "`r?`n" | Where-Object { $_ -match '^誤り：' } | Select-Object -First 15
        $found.Add("$($check.Script) の誤り（$($check.Skill) スキルの手順で直す）：`n$($lines -join "`n")")
    }
}
if ($found.Count -eq 0) { exit 0 }

$text = $found -join "`n"
if ($active) {
    # 2回目は止めずに、残っていることだけを伝える
    [Console]::Error.WriteLine("検査の誤りが残っている：`n$text")
    exit 0
}
@{ decision = 'block'; reason = "終える前に、検査の誤りを直す。`n$text" } | ConvertTo-Json -Compress
exit 0
