# 音声認識はSenseVoiceとSileroのVADで始める

- 状態：有効
- 問題：会話翻訳の最初の音声認識を、候補を比べてから選ぶか、1つで通すかを決める必要があった。
- 決めたこと：sherpa-onnxのSenseVoice Small（int8）＋Silero VADの1エンジンで通す（作者了解）。sherpa-onnxの組み込み方は [sherpa-onnxは静的リンク版のAARを使う](2026-11-06_sherpa-onnxは静的リンク版のAARを使う.md)。
- 理由：確実に動く1エンジンで端から端まで通す方針（[アジャイルに進め毎回実機で触れる版で終える](2026-11-05_アジャイルに進め毎回実機で触れる版で終える.md)）。SenseVoiceは5秒の発話を0.1〜0.2秒で認識し、ある韓国語ベンチマークで平均CER 12.72%（Qwen3-ASR-0.6Bは17.29%）。
- 捨てた案：（保留）Nemotron 3.5 ASR Streaming 0.6B（ネイティブのストリーミング。多言語版をsherpa-onnxで動かせるかが未確認）、Qwen3-ASR-0.6B（sherpa-onnxが公式に対応、2026-03-25版）。比較は不満が出たら行う（`docs/バックログ.md`）。
- 再発を防ぐもの：なし（選択）
