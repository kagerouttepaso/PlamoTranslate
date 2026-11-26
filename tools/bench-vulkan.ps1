<#
.SYNOPSIS
  Android 実機で llama.cpp の Vulkan / OpenCL / CPU バックエンドの動作・訳文一致・速度を比較する。

.DESCRIPTION
  build-android/push に配置した arm64-v8a バイナリ一式を端末の /data/local/tmp/plamo-vk へ送り、
  検出されたデバイス（Vulkan / OpenCL / CPU）で 1 文翻訳と llama-bench を実行する。
  -Build を付けると、Windows のパス長制限を避けて一時フォルダーでクロスコンパイルし、
  strip 済みの成果物を build-android/push に揃える。

.EXAMPLE
  ./tools/bench-vulkan.ps1 -Build
  ./tools/bench-vulkan.ps1
  ./tools/bench-vulkan.ps1 -ModelPath /sdcard/Android/data/app.plamotranslate/files/models/llm/plamo-3-translate-2b-Q4_K_M.gguf
#>
param(
    [switch]$Build,
    [string]$Device = "",
    [string]$ModelPath = "/sdcard/Android/data/app.plamotranslate/files/models/llm/plamo-3-translate-0.8b-Q4_K_M.gguf",
    [int]$Threads = 4,
    [switch]$SkipPush,
    [switch]$KeepRemote
)

$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot -Parent
$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$adb = Join-Path $sdk "platform-tools\adb.exe"
$adbArgs = if ($Device) { @("-s", $Device) } else { @() }
$pushDir = Join-Path $repo "build-android\push"
$remote = "/data/local/tmp/plamo-vk"

if ($Build) {
    $cm = Join-Path $sdk "cmake\3.31.6\bin"
    $ndk = Join-Path $sdk "ndk\29.0.14206865"
    $vk = $env:VULKAN_SDK
    if (-not $vk) { throw "環境変数 VULKAN_SDK が設定されていません" }
    $spirv = Join-Path $vk "Lib\cmake\SPIRV-Headers"
    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    $vsRoot = if (Test-Path $vswhere) { & $vswhere -latest -products * -property installationPath } else { "" }
    $vcvars = Join-Path $vsRoot "VC\Auxiliary\Build\vcvars64.bat"
    if (-not (Test-Path $vcvars)) { throw "vcvars64.bat が見つかりません: $vcvars" }

    $env:PYTHONUTF8 = "1"
    $tmpBuild = Join-Path $env:TEMP "pvk"
    if (Test-Path $tmpBuild) { Remove-Item -Recurse -Force $tmpBuild }

    # 1. リンク用の OpenCL ICD ローダー
    & "$cm\cmake.exe" -S (Join-Path $repo "third_party\OpenCL-ICD-Loader") -B "$tmpBuild\icd" -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$cm\ninja.exe" `
        "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake" `
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release -DBUILD_TESTING=OFF `
        "-DOPENCL_ICD_LOADER_HEADERS_DIR=$repo/third_party/OpenCL-Headers"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & "$cm\cmake.exe" --build "$tmpBuild\icd" --target OpenCL -j 8
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    # 2. llama.cpp（Vulkan + OpenCL + CPU 全バリアント）
    $cmakeArgs = @(
        "-S", (Join-Path $repo "third_party\llama.cpp"),
        "-B", "$tmpBuild\llama",
        "-G", "Ninja",
        "-DCMAKE_MAKE_PROGRAM=$cm/ninja.exe",
        "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake",
        "-DANDROID_ABI=arm64-v8a",
        "-DANDROID_PLATFORM=android-28",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DBUILD_SHARED_LIBS=ON",
        "-DGGML_BACKEND_DL=ON",
        "-DGGML_CPU_ALL_VARIANTS=ON",
        "-DGGML_SYSTEM_ARCH=ARM",
        "-DGGML_NATIVE=OFF",
        "-DGGML_OPENMP=OFF",
        "-DGGML_LLAMAFILE=OFF",
        "-DLLAMA_OPENSSL=OFF",
        "-DGGML_CPU_KLEIDIAI=ON",
        "-DGGML_VULKAN=ON",
        "-DGGML_OPENCL=ON",
        "-DOpenCL_INCLUDE_DIR=$repo/third_party/OpenCL-Headers",
        "-DOpenCL_LIBRARY=$tmpBuild/icd/libOpenCL.so",
        "-DLLAMA_BUILD_TESTS=OFF",
        "-DLLAMA_BUILD_EXAMPLES=OFF",
        "-DLLAMA_BUILD_SERVER=OFF",
        "-DVulkan_INCLUDE_DIR=$vk/Include",
        "-DVulkan_GLSLC_EXECUTABLE=$vk/Bin/glslc.exe",
        "-DSPIRV-Headers_DIR=$spirv"
    ) -join " "

    cmd.exe /c "`"$vcvars`" >nul && `"$cm\cmake.exe`" $cmakeArgs"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $cpuTargets = & "$cm\ninja.exe" -C "$tmpBuild\llama" -t targets |
        ForEach-Object { ($_ -split ':')[0] } |
        Where-Object { $_ -match '^ggml-cpu' } |
        Select-Object -Unique
    $targets = (@("llama-bench", "llama-completion", "ggml-vulkan", "ggml-opencl") + $cpuTargets) -join " "
    cmd.exe /c "`"$vcvars`" >nul && `"$cm\cmake.exe`" --build `"$tmpBuild\llama`" -j 8 --target $targets"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $strip = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
    New-Item -ItemType Directory -Force $pushDir | Out-Null
    Get-ChildItem "$tmpBuild\llama\bin" -File | Where-Object { $_.Name -ne "vulkan-shaders-gen.exe" } | ForEach-Object {
        $dst = Join-Path $pushDir $_.Name
        Copy-Item $_.FullName $dst -Force
        & $strip --strip-unneeded $dst 2>$null
    }
}

if (-not (Test-Path (Join-Path $pushDir "llama-bench"))) {
    throw "バイナリがありません。先に -Build を付けて実行してください: $pushDir"
}

if (-not $SkipPush) {
    & $adb @adbArgs shell "rm -rf $remote && mkdir -p $remote"
    & $adb @adbArgs push "$pushDir\." "$remote/" | Out-Null
    & $adb @adbArgs shell "chmod +x $remote/llama-bench $remote/llama-completion"
}

try {
    $envPrefix = "LD_LIBRARY_PATH=.:/vendor/lib64 GGML_OPENCL_KERNEL_CACHE_DIR=$remote/cl-cache"
    Write-Output "=== 検出デバイス ==="
    $devOut = & $adb @adbArgs shell "cd $remote && $envPrefix ./llama-bench --list-devices 2>&1"
    $devOut | ForEach-Object { Write-Output $_ }

    $devices = @()
    foreach ($line in $devOut) {
        if ($line -match '^\s*(Vulkan\d+|OpenCL)\s*:') {
            $devices += $Matches[1]
        }
    }

    $promptKo = "<|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>ko<|plamo:msg|>내일 몇 시에 만날까요?<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja<|plamo:key|>form<|plamo:val|>polite<|plamo:msg|>"
    $tmp = New-TemporaryFile
    [System.IO.File]::WriteAllText($tmp, $promptKo, (New-Object System.Text.UTF8Encoding $false))
    & $adb @adbArgs push $tmp "$remote/prompt_ko.txt" | Out-Null
    Remove-Item $tmp

    Write-Output "`n=== 1文翻訳（訳文一致・壁時計時間）==="
    foreach ($dev in $devices) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $out = & $adb @adbArgs shell "cd $remote && $envPrefix ./llama-completion -m $ModelPath -f prompt_ko.txt -n 64 --temp 0 --device $dev -ngl 99 -t $Threads -no-cnv --no-display-prompt 2>/dev/null"
        $sw.Stop()
        Write-Output ("{0,-8} ({1:N2}s): {2}" -f $dev, $sw.Elapsed.TotalSeconds, ($out -join " "))
    }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $out = & $adb @adbArgs shell "cd $remote && $envPrefix ./llama-completion -m $ModelPath -f prompt_ko.txt -n 64 --temp 0 --device none -ngl 0 -t $Threads -no-cnv --no-display-prompt 2>/dev/null"
    $sw.Stop()
    Write-Output ("{0,-8} ({1:N2}s): {2}" -f "CPU", $sw.Elapsed.TotalSeconds, ($out -join " "))

    Write-Output "`n=== llama-bench ==="
    foreach ($dev in $devices) {
        Write-Output "--- $dev ---"
        & $adb @adbArgs shell "cd $remote && $envPrefix ./llama-bench -m $ModelPath --device $dev -ngl 99 -t $Threads -p 128,512 -n 64 -r 3 -fa 0,1 2>/dev/null"
        if ($dev -like "Vulkan*") {
            Write-Output "--- $dev (GGML_VK_DISABLE_COOPMAT=1) ---"
            & $adb @adbArgs shell "cd $remote && $envPrefix GGML_VK_DISABLE_COOPMAT=1 ./llama-bench -m $ModelPath --device $dev -ngl 99 -t $Threads -p 128,512 -n 64 -r 3 -fa 1 2>/dev/null"
        }
    }
    Write-Output "--- CPU ($Threads threads) ---"
    & $adb @adbArgs shell "cd $remote && $envPrefix ./llama-bench -m $ModelPath --device none -ngl 0 -t $Threads -p 128,512 -n 64 -r 3 -fa 0,1 2>/dev/null"
} finally {
    if (-not $KeepRemote) {
        & $adb @adbArgs shell "rm -rf $remote" | Out-Null
    }
}
