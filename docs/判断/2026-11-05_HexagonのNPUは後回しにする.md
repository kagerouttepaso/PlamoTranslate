# HexagonのNPUは後回しにする

- 状態：有効
- 問題：推論の実行先として、Hexagon NPUのバックエンドも候補にあった。
- 決めたこと：Hexagon NPUのバックエンドは使わず、GPU（OpenCL）で目標に届かないときだけ検討する。
- 理由：Hexagon SDKが要り、準備が重い。
- 再発を防ぐもの：なし（選択）
