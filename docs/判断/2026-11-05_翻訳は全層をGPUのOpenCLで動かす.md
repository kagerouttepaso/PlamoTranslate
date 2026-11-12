# 翻訳は全層をGPUのOpenCLで動かす

- 状態：有効
- 問題：REDMAGIC 11 ProはNubia独自の性能制御で、前面のアプリのCPUクロックを全コア1.79GHz程度に抑え、性能ヒント（APerformanceHint）でも外れない。CPUで動かすと画面翻訳の1画面に20秒を超えた。
- 決めたこと：llama.cppを `GGML_OPENCL=ON` でビルドし、全層をAdreno GPUに載せる。
- 理由：CLIの `llama-bench` で、プロンプト処理がGPU 1109〜1178／CPU 115〜229トークン/秒、8行まとめの翻訳1回がGPU 0.97秒／CPU 2.55秒で、訳文は一致した。画面翻訳の1画面が20.5秒から5.5秒になった。
- 捨てた案：CPU（KleidiAI有効）で動かす（最初の実装）。Qualcomm以外の端末の扱いは [Qualcomm以外の端末ではCPUで動かし計算部分を実行時に読み込む](2026-11-06_Qualcomm以外の端末ではCPUで動かし計算部分を実行時に読み込む.md)。
- 再発を防ぐもの：なし（選択）
