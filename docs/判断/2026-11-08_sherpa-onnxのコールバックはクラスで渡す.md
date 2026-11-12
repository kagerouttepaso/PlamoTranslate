# sherpa-onnxのコールバックはクラスで渡す

- 状態：有効
- 問題：0.4.0の最初のビルドで、REDMAGICでSupertonic 3を押すと `NoSuchMethodError` で落ちた。ネイティブ側はコールバックの `invoke(float[]): Integer` を名前と型で探すが、Kotlinのラムダはinvokedynamicで作られてこのメソッドを持たない。
- 決めたこと：`OfflineTts` のコールバックを `object : (FloatArray) -> Int` で渡す。
- 理由：型を明示したクラスなら、`invoke(float[]): Integer` を必ず持つ。
- 再発を防ぐもの：理由を `core-tts` の `SupertonicSpeaker` のコメントに書いた。一般化した規則は記録が無い。
