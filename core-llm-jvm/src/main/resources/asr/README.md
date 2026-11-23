# Qwen3-ASR の試走音声

`reference.s16le` は、Supertonic 3 で英語の「The door is open.」を合成したもの。sherpa-onnx 2.0.1 を CPU 4スレッドで動かし、アプリの既定と同じ声（`sid=0`）、速さ（`1.0`）、生成ステップ数（`8`）で、言語に `en` を指定した。

モデルはアプリの `ModelCatalog.speech` と同じ7ファイルを使い、バイト数と SHA-256 を照合した。

- [sherpa-onnx 向け int8 版](https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/tree/cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4)：duration predictor、text encoder、vector estimator と `tts.json`、`unicode_indexer.bin`、`voice.bin`。
- [Supertonic 3 公式](https://huggingface.co/Supertone/supertonic-3/tree/3cadd1ee6394adea1bd021217a0e650ede09a323/onnx)：vocoder だけは fp32 版を使用。

44.1 kHz のモノラル出力を SciPy 1.18.1 の `resample_poly`（比率 160/441、`window=('kaiser', 5.0)`）で 16 kHz に変換した。ピーク比 −50 dB を超えた最初と最後のサンプルから前後に各0.2秒を残し、先頭1,277・末尾3,302サンプルを除いた。振幅を32,768倍し、最近接整数に丸めて符号付き16 bit の範囲に収め、リトルエンディアンのヘッダーなし PCM として保存した。

**再配布の根拠：** [BigScience Open RAIL-M License 第6項](../../../../../app/src/main/assets/licenses/supertonic-openrail-m.txt#L128)では、ライセンスに別段の定めがある場合を除き、許諾者はモデルの生成出力に権利を主張しない。出力とその利用には生成者が責任を負い、ライセンスに反する利用はできない。ここに同梱するのは生成した音声で、モデルの重みではない。
