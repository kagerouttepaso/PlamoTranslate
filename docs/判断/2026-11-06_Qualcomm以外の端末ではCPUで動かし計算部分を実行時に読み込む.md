# Qualcomm以外の端末ではCPUで動かし計算部分を実行時に読み込む

- 状態：有効
- 問題：APKを配るため、REDMAGIC 11 Pro以外の端末でも動く必要があった。自作JNIが端末のOpenCLのライブラリに直接リンクしていたので、OpenCLの無い端末（Pixel など）では起動時にライブラリを読み込めず落ちた。llama.cppのOpenCLバックエンドはAdreno向けに作られ、Mali（Tensor・Exynos・Dimensity）で正しく速く動く保証が無い。
- 決めたこと：llama.cppを共有ライブラリにし、計算部分（`libggml-opencl.so`・`libggml-cpu-*.so`）を実行時に読み込む（`GGML_BACKEND_DL`）。Qualcomm（`Build.SOC_MANUFACTURER` が `QTI`）の端末はGPU（全層）、それ以外の端末とGPUで読み込めなかったときはCPUだけで動かす。CPUの版はARMの世代ごとに7つ作り、端末で動く一番速い版を選ぶ。
- 理由：実行時に読み込めば、OpenCLの無い端末でも起動でき、端末ごとに動く一番速い版を選べる。
- 捨てた案：OpenCLに直接リンクしたまま、REDMAGIC 11 Proだけで動く形にする。
- 再発を防ぐもの：`docs/設計/アーキテクチャ.md`「ビルド時の構成」「GPUとCPUの切り替え」
