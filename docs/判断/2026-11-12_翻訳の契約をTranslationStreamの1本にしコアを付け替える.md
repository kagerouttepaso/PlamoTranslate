# 翻訳の契約をTranslationStreamの1本にしコアを付け替える

- 状態：有効
- 問題：翻訳の入口が2つあり（画面・カメラは `BatchTranslator`、テキスト・会話は `TranslationEngine.translate`）、受け取り口とコアの生成も別々だった。入力の作り方がコアの選び方に応じて呼ぶ側に漏れていた。
- 決めたこと：テキスト・会話・画面・カメラの翻訳を、どれも `app/translation` の `Translations.open` で開く `TranslationStream` に通す。1件ずつか同時か、行をつなぐか長文を分けるかは、裏のコア（`SequentialCore`・`ParallelCore`）が決める。流れは訳し始めるたびに `EngineHolder` が持つ今のエンジンを受け取る。エンジンを直接動かす（`SingleRequest`）のは、初回の空訳しと速さの計測だけにする。
- 理由：作者の指示「入口・受け取り口・データフローは1種類、コアを付け替える」。訳し始めるたびに今のエンジンを受け取れば、0.8B・2Bを切り替えた後は次の依頼から新しいモデルで訳せる。
- 捨てた案：`BatchTranslator` と1文の翻訳の2つの入口を残す。
- 再発を防ぐもの：`CLAUDE.md`「設計の幹を守る」、`docs/設計/アーキテクチャ.md`「設計の原則」、`docs/設計/翻訳コア.md`「翻訳の契約」。単体テスト `TranslationStreamTest`。
