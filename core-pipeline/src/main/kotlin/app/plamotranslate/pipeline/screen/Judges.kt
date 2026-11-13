package app.plamotranslate.pipeline.screen

/**
 * 開始の判定A（docs/設計/画面翻訳.md「判定器」）：「画面が落ち着いたので訳し始めてよいか」。自動で訳すときの待ちの間だけ使う。
 * 雑音の除き方（中央値・動いたます目の除外）はここだけで使い、有効性の判定Bには渡さない。
 */
class StartJudge(private val p: StartParams) {
    private val recent = ArrayDeque<Thumb>()
    private var previous: Thumb? = null
    private var stableSince = 0L
    private var since = 0L

    /** 待ちを始める。 */
    fun reset(now: Long) {
        recent.clear()
        previous = null
        stableSince = now
        since = now
    }

    /**
     * 新しく届いた格子 [thumb]（届いていなければ null：画面が止まっている印）を入れ、訳し始めてよいかを返す。
     * [own] は比べないます目（自分の浮遊ボタンなど）。
     */
    fun feed(thumb: Thumb?, now: Long, own: BooleanArray?): Boolean {
        if (thumb != null) {
            recent.addLast(thumb)
            while (recent.size > p.medianOf) recent.removeFirst()
            val t = if (p.medianOf >= 3) ScreenChange.median(recent.toList()) else thumb
            val active = if (p.ignoreActive && recent.size >= 3) ScreenChange.active(recent.toList()) else null
            val mask = own?.let { ScreenChange.union(it, active) } ?: active
            val prev = previous
            previous = t
            if (prev == null || ScreenChange.changedFraction(prev, t, mask) >= p.stable) stableSince = now
        }
        val waitedTooLong = p.maxWaitMs != null && now - since >= p.maxWaitMs
        return now - stableSince >= p.settleMs || waitedTooLong
    }
}

/** 有効性の判定Bの判断。 */
sealed interface Validity {
    data object Valid : Validity

    /** 無効になった。[reason] は理由（ログ用）、[amount] は変わったます目の割合。 */
    data class Invalid(val reason: String, val amount: Float) : Validity
}

/**
 * 有効性の判定B（docs/設計/画面翻訳.md「判定器」）：「訳した原文が、まだ同じ場所にあるか」。訳している間と表示している間に使う。
 * 基準は、訳すときに撮った画面（自分の表示を隠して撮ったもの）の格子。新しく届いた格子（自分の表示が写っている）と、
 * 自分の表示の外だけを比べる。
 *
 * 1. 画面が丸ごと切り替わった：中央値を通さない1枚の、自分の表示の外の変化が [ValidityParams.switch] 以上。
 * 2. 原文が動いた・消えた：訳の枠の外周（原文のすぐ周り）の変化が [ValidityParams.ring] 以上（null なら見ない）。
 * 3. 画面が変わった（自動で訳すときだけ）：自分の表示の外の変化が [ValidityParams.outside] 以上（null なら見ない）。
 *
 * 2026-11-12までは、開始の判定Aの雑音の除き方（動いたます目の除外）がここにも効き、画面の8割を見ないまま判定して、
 * Discordで戻ってもチャンネル一覧の上に前の訳が残った。ここでは動いたます目を外さない。
 */
class ValidityJudge(private val p: ValidityParams) {
    private var reference: Thumb? = null
    private val recent = ArrayDeque<Thumb>()

    /** 基準の画面を決めて判定を始める。 */
    fun begin(reference: Thumb) {
        this.reference = reference
        recent.clear()
    }

    /** 判定をやめる。 */
    fun end() {
        reference = null
        recent.clear()
    }

    val active: Boolean get() = reference != null

    /**
     * 新しく届いた格子 [raw] で判定する。[boxes] は訳の枠（まだ出していなければ空）、[own] は自分の表示全部（訳の枠・上部の表示・浮遊ボタン）。
     * [watchOutside] は規則3を使うか（自動で訳すとき）。
     */
    fun feed(raw: Thumb, boxes: List<Box>, own: List<Box>, watchOutside: Boolean): Validity {
        val ref = reference ?: return Validity.Valid
        val ownMask = ScreenChange.mask(raw, own)
        val switched = ScreenChange.changedFraction(ref, raw, ownMask)
        if (switched >= p.switch) return Validity.Invalid("画面が切り替わった", switched)

        recent.addLast(raw)
        while (recent.size > p.medianOf) recent.removeFirst()
        val t = if (p.medianOf >= 3 && recent.size >= 3) ScreenChange.median(recent.toList()) else raw

        val ringLimit = p.ring
        if (ringLimit != null && boxes.isNotEmpty()) {
            val ring = ScreenChange.ring(t, boxes, p.ringMargin)
            // 外周のうち、自分の表示（上部の表示・浮遊ボタン・ほかの訳の枠）にかかるます目は比べない
            val compare = BooleanArray(ring.size) { !ring[it] || ownMask[it] }
            if (ScreenChange.counted(compare) > 0) {
                val moved = ScreenChange.changedFraction(ref, t, compare)
                if (moved >= ringLimit) return Validity.Invalid("原文の周りが変わった", moved)
            }
        }
        val outside = p.outside
        if (watchOutside && outside != null) {
            val changed = ScreenChange.changedFraction(ref, t, ownMask)
            if (changed >= outside) return Validity.Invalid("画面が変わった", changed)
        }
        return Validity.Valid
    }

    /** 訳の枠を隠して撮った格子 [clean] で、枠の下（原文）が変わったかを見る（触れたときにのぞく）。 */
    fun peek(clean: Thumb, boxes: List<Box>): Validity {
        val ref = reference ?: return Validity.Valid
        if (boxes.isEmpty()) return Validity.Valid
        val changed = ScreenChange.changedFraction(ref, clean, ScreenChange.invert(ScreenChange.mask(clean, boxes)))
        return if (changed >= p.peek) Validity.Invalid("訳の枠の下が変わった", changed) else Validity.Valid
    }
}
