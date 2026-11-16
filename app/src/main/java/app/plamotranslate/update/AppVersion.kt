package app.plamotranslate.update

/**
 * 版（`0.4.0`、リリースのタグ `v0.4.0`、ベータは `v0.11.0-beta.1`）を比べる。
 * 数字の部分を左から順に比べ、足りない桁は0とみなす（`0.4` と `0.4.0` は同じ）。
 * 数字が同じなら、`-` の後ろ（ベータの識別子）が無い方が新しい（`0.11.0-beta.2 < 0.11.0`）。ベータの識別子どうしは、
 * `.` で区切った部分を左から、数字は数として、ほかは文字として比べる（`beta.2 < beta.10`）。
 * ベータを入れた端末にも、後で出る正式版が新しい版として届くようにするため。
 */
object AppVersion {
    /** `v0.4.0` → [0, 4, 0]。数字が1つも無ければ null。 */
    fun parse(text: String): List<Int>? {
        val core = text.trim().removePrefix("v").removePrefix("V").takeWhile { it.isDigit() || it == '.' }
        val parts = core.split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
        return parts.ifEmpty { null }
    }

    /** `v0.11.0-beta.1` → [beta, 1]。ベータの識別子が無ければ空。 */
    fun prerelease(text: String): List<String> {
        val rest = text.trim().substringAfter('-', "").substringBefore('+')
        return rest.split('.').filter { it.isNotEmpty() }
    }

    /** [candidate] が [current] より新しいか。どちらかが読めなければ false（更新を勧めない）。 */
    fun isNewer(candidate: String, current: String): Boolean = (compare(candidate, current) ?: 0) > 0

    /** 版を比べる（正なら [a] が新しい）。どちらかが読めなければ null。 */
    fun compare(a: String, b: String): Int? {
        val x = parse(a) ?: return null
        val y = parse(b) ?: return null
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        val p = prerelease(a)
        val q = prerelease(b)
        if (p.isEmpty() || q.isEmpty()) return q.size.coerceAtMost(1) - p.size.coerceAtMost(1)
        for (i in 0 until maxOf(p.size, q.size)) {
            // 短い方が古い（beta < beta.1）
            val s = p.getOrNull(i) ?: return -1
            val t = q.getOrNull(i) ?: return 1
            val m = s.toIntOrNull()
            val n = t.toIntOrNull()
            val c = when {
                m != null && n != null -> m.compareTo(n)
                m != null -> -1
                n != null -> 1
                else -> s.compareTo(t)
            }
            if (c != 0) return c
        }
        return 0
    }
}
