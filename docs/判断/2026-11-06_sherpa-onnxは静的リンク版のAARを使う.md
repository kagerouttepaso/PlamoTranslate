# sherpa-onnxは静的リンク版のAARを使う

- 状態：有効
- 問題：漫画用OCRがONNX Runtime 1.31.1を使っており、sherpa-onnxが持つONNX Runtimeとライブラリ名がぶつかる。
- 決めたこと：sherpa-onnxは `sherpa-onnx-static-link-onnxruntime` のAARを使う。x86用だけ別ファイルで持つ `libonnxruntime.so` は同梱から外す（アプリはarm64だけ）。
- 理由：ONNX Runtimeを静的に組み込んだ版なら、漫画用OCRのONNX Runtimeとライブラリ名がぶつからない。代わりにONNX RuntimeがAPKに2つ入る（1つにまとめる案は `docs/バックログ.md`「実測で決める事項」）。
- 再発を防ぐもの：なし（選択）
