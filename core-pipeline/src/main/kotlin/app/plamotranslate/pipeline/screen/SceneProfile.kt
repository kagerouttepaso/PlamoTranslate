package app.plamotranslate.pipeline.screen

/**
 * 場面の設定（docs/設計/画面翻訳.md「場面の設定」）。差し替えるブロックの設定の組み合わせに名前を付けたもの。
 * 制御（状態遷移）は場面で変えない。
 */
data class SceneProfile(
    /** 文字認識に渡す1枚を、何枚の画面の中央値で作るか（1なら1枚そのまま）と、撮る間隔。 */
    val stackFrames: Int,
    val stackIntervalMs: Long,
    /** 白縁取り・白抜きの文字にも強い読み方をするか。 */
    val outlinedText: Boolean,
    val start: StartParams,
    val validity: ValidityParams,
) {
    /**
     * 触れるまで訳を残す組（メニューの「訳の消え方」。2026-11-13、作者の要望「背景のキャラが走り回ると訳が消える」）。
     * 有効性の判定Bのうち、画面の動きで消す規則（原文の周り・画面全体の変化）を見ない。
     * 画面の丸ごとの切り替わり、ユーザー補助の合図、触れたときののぞきでは今までどおり消える。
     */
    fun untilTouched(): SceneProfile = copy(validity = validity.copy(ring = null, outside = null))

    companion object {
        /** 通常（チャット・ウェブ・漫画）。 */
        val NORMAL = SceneProfile(
            stackFrames = 1,
            stackIntervalMs = 0,
            outlinedText = false,
            // 2%未満の変化が0.6秒続いたら止まった（点滅するカーソルや小さなアイコンは許す。スクロールの慣性が止まり、ページ送りの動きが終わるのを待つ）
            start = StartParams(stable = 0.02f, settleMs = 600, maxWaitMs = null, medianOf = 1, ignoreActive = false),
            // 自分の表示の外の6%が変われば変わった（数行のスクロールで超える）
            validity = ValidityParams(outside = 0.06f, ring = 0.3f, switch = 0.5f, medianOf = 1),
        )

        /**
         * 動く画面（ゲーム）。背景が動き続けるので、開始の判定Aは直近5回の中央値で比べて動いたます目を外し、最大3秒で訳し始める。
         * 有効性の判定Bは、画面全体の変化を見ず（背景が動くため）、原文の外周を3回の中央値で比べる。
         * 背景の動きを除くために判定Bが原文の外周を見えなくする（2026-11-12までの作り）ことはしない。
         */
        val MOTION = SceneProfile(
            stackFrames = 5,
            stackIntervalMs = 110,
            outlinedText = true,
            start = StartParams(stable = 0.04f, settleMs = 600, maxWaitMs = 3000, medianOf = 5, ignoreActive = true),
            validity = ValidityParams(outside = null, ring = 0.3f, switch = 0.6f, medianOf = 3, peekFrames = 3, peekIntervalMs = 80),
        )
    }
}

/**
 * 開始の判定Aの設定。直前の格子との変化が [stable] 未満の状態が [settleMs] 続いたら訳し始める。[maxWaitMs] を超えたら止まるのを待たない。
 * [medianOf] 回の格子の中央値で比べ、[ignoreActive] なら動いたます目（動く背景）を外す。
 */
data class StartParams(
    val stable: Float,
    val settleMs: Long,
    val maxWaitMs: Long?,
    val medianOf: Int,
    val ignoreActive: Boolean,
)

/**
 * 有効性の判定Bの設定。
 * - [switch]：中央値を通さない1枚で、自分の表示の外のこれだけが変われば、画面が丸ごと切り替わった。
 * - [ring]：原文の外周（訳の枠の周り2ます）のこれだけが変われば、原文が動いた・消えた（null なら見ない）。
 * - [outside]：自分の表示の外のこれだけが変われば、画面が変わった（自動で訳すときだけ。null なら見ない）。
 * - [medianOf]：外周と外の変化を、何回の格子の中央値で比べるか。
 * - [peek]：触れたときに訳の枠を隠してのぞき、枠の下のこれだけが変われば、原文が変わった。
 * - [peekFrames]・[peekIntervalMs]：のぞくときに撮る枚数と間隔（3枚以上なら中央値で動く背景を消す）。
 */
data class ValidityParams(
    val outside: Float?,
    val ring: Float?,
    val switch: Float,
    val medianOf: Int,
    val peek: Float = 0.15f,
    val ringMargin: Int = 2,
    val peekFrames: Int = 1,
    val peekIntervalMs: Long = 0,
)
