# Android に Vulkan バックエンドを採用しない

- 状態：有効
- 問題：Android 版は現在 Qualcomm 端末（Snapdragon）で OpenCL を使い、非 Qualcomm 端末（Dimensity / Mali 等）は CPU で動かしている。非 Qualcomm 端末でも GPU を使えるか、また Qualcomm 端末で OpenCL より Vulkan の方が速いかを確かめるため、Android 版に `ggml-vulkan` と比較用の隔離プロセス（`:gpu_probe`）を組み込んで実測した。
- 決めたこと：Android 版には Vulkan バックエンドを採用しない。実験コード（`libggml-vulkan.so` のビルド・同梱、`:gpu_probe` の比較画面、`tools/bench-vulkan.ps1`）はすべて差し戻し、Android は現行の「Qualcomm は OpenCL、それ以外は CPU」を維持する（Windows PC 版の Vulkan 利用は維持する）。
- 理由：REDMAGIC 11 Pro（Snapdragon 8 Elite Gen 5 / Adreno 840）と Xiaomi 15T Pro（Dimensity 9400+ / Mali-G925-Immortalis MC12）の 2 機種で、CLI（`llama-bench`・`llama-completion`）およびアプリ内計測（PLaMo 3 Translate 0.8B Q4_K_M、v0.14.1-beta.5 試作）を行った結果、どちらの系統でも採用する価値がなかった。
  1. **非 Qualcomm（Mali-G925 / 15T Pro）**：Vulkan 既定では `VK_KHR_cooperative_matrix` によりプロンプト処理（prefill）が約 3.4 倍遅くなった（`GGML_VK_DISABLE_COOPMAT=1` で初回 1517ms → 418ms に改善）。ただし回避しても、生成 38.5 tok/s・同時 4 件 22 tok/s に留まり、CPU（`android_armv9.0_1`：初回 132ms・生成 60.6 tok/s・同時 4 件 77 tok/s）の方が圧倒的に速かった。
  2. **Qualcomm（Adreno 840 / REDMAGIC 11 Pro）**：Vulkan 既定では `VK_KHR_shader_integer_dot_product` のドライバ不具合で訳文が空になった。`GGML_VK_DISABLE_INTEGER_DOT_PRODUCT=1` で訳文は出るようになるが、初回 855ms・生成 29.1 tok/s・同時 4 件 20 tok/s（CLI `llama-bench` も `pp128=68.1` で `pp512` は完走せず）と、OpenCL（初回 66ms・生成 62.7 tok/s・同時 4 件 69 tok/s、CLI `pp128=1174.1`・`pp512=1245.4`）や CPU（`android_armv9.2_2`：初回 97ms・生成 99.2 tok/s・同時 4 件 145 tok/s）より大幅に遅かった。
  3. **APK サイズと保守コスト**：`libggml-vulkan.so` を同梱すると APK が約 +10.62 MB（展開後約 34.6 MB。`libggml-opencl.so` は約 0.94 MB）増える。さらに SoC・ドライバごとの機能フラグ回避（`coopmat`・`int_dot` 等）と Vulkan SDK 依存のビルド手順を抱える割に、速度・安定性の両面で利点がない。

## 実測の要約（アプリ内計測、PLaMo 3 Translate 0.8B Q4_K_M）

| 端末 | バックエンド | 読み込み | 最初の文字 | 生成（1文） | 同時4件 | 備考 |
| --- | --- | --- | --- | --- | --- | --- |
| Xiaomi 15T Pro（Dimensity 9400+ / Mali-G925） | Vulkan 既定 | 2162 ms | 1517 ms | 39.3 tok/s | 11 tok/s | `coopmat` により prefill が大幅に遅い |
| Xiaomi 15T Pro | Vulkan（`coopmat` 無効） | 2331 ms | 418 ms | 38.5 tok/s | 22 tok/s | 既定より改善するが CPU に大きく劣る |
| Xiaomi 15T Pro | OpenCL | — | — | — | — | 非対応（`unsupported GPU`） |
| Xiaomi 15T Pro | CPU（`android_armv9.0_1`） | 3446 ms | 132 ms | 60.6 tok/s | 77 tok/s | 最速 |
| REDMAGIC 11 Pro（Snapdragon 8 Elite Gen 5 / Adreno 840） | Vulkan 既定 / `coopmat` 無効 | — | — | — | — | 訳文が空（`int_dot` ドライバ不具合） |
| REDMAGIC 11 Pro | Vulkan（`int_dot` 無効） | 912 ms | 855 ms | 29.1 tok/s | 20 tok/s | 訳文は出るが OpenCL・CPU より大幅に遅い |
| REDMAGIC 11 Pro | OpenCL（Adreno 840） | 2457 ms | 66 ms | 62.7 tok/s | 69 tok/s | 安定・高速 |
| REDMAGIC 11 Pro | CPU（`android_armv9.2_2`） | 4020 ms | 97 ms | 99.2 tok/s | 145 tok/s | 最速（SME + KleidiAI） |

- 再発を防ぐもの：Android 向けに Vulkan を再検討するときは、将来の `llama.cpp` の `ggml-vulkan` 改修や主要 Android SoC の Vulkan ドライバ改善により、非 Qualcomm 端末で CPU を明確に上回り、かつ Adreno 等のドライバ不具合が解消されたことを CLI とアプリ内実測の両方で確かめてから判断する。
