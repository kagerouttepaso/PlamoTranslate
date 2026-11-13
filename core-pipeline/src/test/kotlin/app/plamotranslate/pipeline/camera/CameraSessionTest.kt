package app.plamotranslate.pipeline.camera

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.camera.CameraSession.Effect
import app.plamotranslate.pipeline.camera.CameraSession.Event
import app.plamotranslate.pipeline.camera.CameraSession.Settings
import app.plamotranslate.pipeline.layout.TextLine
import app.plamotranslate.pipeline.screen.Box
import app.plamotranslate.pipeline.translation.SequentialCore
import app.plamotranslate.pipeline.translation.intakeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionTest {
    private val menu = Settings(source = null, target = Language.JA, table = true)

    /** 表・メニューのモードで、流れを開き、渡しておく数を [intake] にした制御。 */
    private fun session(intake: Int = 2, settings: Settings = menu) = CameraSession<Unit>(
        lineOf = { text, box -> TextLine(text, box, kana = false, latin = true) },
    ).also {
        it.handle(Event.SettingsChanged(settings))
        assertTrue(it.handle(Event.EngineChanged(intake)).any { e -> e is Effect.OpenStream })
        it.handle(Event.StreamOpened)
    }

    /** 縦に離した1行ずつの読み取り（表・メニューのモードでは1行が1つの段落）。 */
    private fun lines(vararg texts: String, x: Int = 0) =
        texts.mapIndexed { i, t -> Reading(Box(x, i * 200, x + 400, i * 200 + 40), t, Unit) }

    private fun submitted(effects: List<Effect>): List<String> =
        effects.filterIsInstance<Effect.Submit>().flatMap { e -> e.segments.map { it.text } }

    private fun ids(effects: List<Effect>): List<Long> =
        effects.filterIsInstance<Effect.Submit>().flatMap { e -> e.segments.map { it.id } }

    private val screen = Box(0, 0, 1000, 2000)

    /** 2回読んで、読み取りをそろえる。 */
    private fun CameraSession<Unit>.readTwice(vararg texts: String, at: Box? = screen): List<Effect> {
        handle(Event.Read(lines(*texts), at))
        return handle(Event.Read(lines(*texts), at))
    }

    @Test
    fun 渡しておく数だけ渡し確定するたびに次を渡す() {
        val s = session(intake = 2)
        val texts = arrayOf("first line here", "second line here", "third line here", "fourth line here")
        val first = s.readTwice(*texts)
        assertEquals(2, submitted(first).size)
        // 読み続けても、空きが無ければ渡さない
        assertTrue(submitted(s.handle(Event.Read(lines(*texts), screen))).isEmpty())
        // 途中の訳では渡し直さない
        assertTrue(s.handle(Event.Partial(ids(first).first(), "途中")).isEmpty())
        // 1件確定したら、次の1件を渡す
        assertEquals(1, submitted(s.handle(Event.Final(ids(first).first(), "訳"))).size)
        assertEquals(2, s.inFlightCount)
    }

    @Test
    fun 渡しておく数は流れのコアが決める() {
        // 1件ずつのコアは行をつないで1回で訳すので、つなげる数まで渡す
        assertEquals(SequentialCore.MAX_LINES, intakeFor(1))
        assertEquals(4, intakeFor(4))
    }

    @Test
    fun 同時に訳す数の設定が変わったら空きも変わる() {
        val s = session(intake = 1)
        val texts = arrayOf("first line here", "second line here", "third line here")
        assertEquals(1, submitted(s.readTwice(*texts)).size)
        // 増えた分を、その場で渡す
        assertEquals(2, submitted(s.handle(Event.IntakeChanged(3))).size)
    }

    @Test
    fun 読み揺れの同じ文は訳がまだ出ていなくても頼み直さない() {
        val s = session(intake = 4)
        assertEquals(listOf("menu item number one"), submitted(s.readTwice("menu item number one")))
        // 1文字違って読めた（同じ行）。訳はまだ1文字も出ていない
        repeat(3) { s.handle(Event.Read(lines("menu item numbcr one"), screen)) }
        assertEquals(1, s.inFlightCount)
    }

    @Test
    fun 段落の原文が大きく変わっても渡している間は待ち前の訳を出し続ける() {
        val s = session(intake = 4, settings = menu.copy(table = false))
        // 文章のモード：近い2行が1つの段落になる
        fun para(vararg texts: String) = texts.mapIndexed { i, t -> Reading(Box(0, i * 50, 600, i * 50 + 40), t, Unit) }
        s.handle(Event.Read(para("the first sentence of text"), screen))
        val id = ids(s.handle(Event.Read(para("the first sentence of text"), screen))).single()
        s.handle(Event.Final(id, "最初の文"))
        // 次の行が読めて段落が伸びた：同じ段落（番号）なので、新しい版の訳が出るまで前の訳を出す
        s.handle(Event.Read(para("the first sentence of text", "and the second one follows"), screen))
        val grown = s.handle(Event.Read(para("the first sentence of text", "and the second one follows"), screen))
        assertEquals(listOf("the first sentence of text and the second one follows"), submitted(grown))
        assertEquals("最初の文", s.boxes().single().translation)
        // 渡している間に、また少し違って読めても頼み直さない
        repeat(2) { s.handle(Event.Read(para("the first sentence of text", "and the second one fol1ows"), screen)) }
        assertEquals(1, s.inFlightCount)
    }

    @Test
    fun 見えていない段落は渡さない() {
        val s = session()
        val visible = Box(0, 0, 1000, 100)
        assertEquals(listOf("shown on screen"), submitted(s.readTwice("shown on screen", "hidden below the screen", at = visible)))
    }

    @Test
    fun 画面の中央に近い段落から渡し範囲が分からなければ上から渡す() {
        val texts = arrayOf("top of the page", "middle of the page", "bottom of the page")
        assertEquals(listOf("middle of the page"), submitted(session(intake = 1).readTwice(*texts, at = Box(0, 100, 400, 340))))
        assertEquals(listOf("top of the page"), submitted(session(intake = 1).readTwice(*texts, at = null)))
    }

    @Test
    fun 一時停止中は読み取りがそろった段落を先にそろっていない段落も渡す() {
        val s = session(intake = 4)
        s.handle(Event.IntakeChanged(0))
        // 上の行は2回読めてそろい、下の行は1回だけ。画面の中央に近いのは下の行
        s.handle(Event.Read(lines("read twice so settled", "read once only"), Box(0, 0, 400, 500)))
        s.handle(Event.Read(lines("read twice so settled"), Box(0, 0, 400, 500)))
        s.handle(Event.Paused)
        // 一時停止の後のコマは捨てる
        assertTrue(s.handle(Event.Read(lines("another frame text"), screen)).isEmpty())
        assertEquals(listOf("read twice so settled", "read once only"), submitted(s.handle(Event.IntakeChanged(2))))
    }

    @Test
    fun 一時停止の後に見えている訳がそろったら一度だけ知らせる() {
        val s = session(intake = 4)
        val id = ids(s.readTwice("only one line here")).single()
        assertFalse(s.handle(Event.Paused).contains(Effect.VisibleComplete))
        assertTrue(s.handle(Event.Final(id, "訳")).contains(Effect.VisibleComplete))
        assertFalse(s.handle(Event.Final(999, "古い番号")).contains(Effect.VisibleComplete))
    }

    @Test
    fun 空きがあれば読み取りがそろっていない段落も映している間に渡す() {
        val s = session(intake = 4)
        // 2回読めたが読み揺れで一致しない行（枠は出る）と、2回とも同じに読めた行
        s.handle(Event.Read(lines("stable line of text", "shaky line abcdef"), screen))
        val effects = s.handle(Event.Read(lines("stable line of text", "quite different words"), screen))
        assertEquals(listOf("stable line of text", "quite different words"), submitted(effects))
    }

    @Test
    fun 空きが足りなければ読み取りがそろった段落を先に渡す() {
        val s = session(intake = 1)
        // 画面の中央に近いのは、読み揺れで一致しない下の行
        val view = Box(0, 0, 400, 400)
        s.handle(Event.Read(lines("stable line of text", "shaky line abcdef"), view))
        val effects = s.handle(Event.Read(lines("stable line of text", "quite different words"), view))
        assertEquals(listOf("stable line of text"), submitted(effects))
    }

    @Test
    fun 訳している途中の訳は原文が揺れても段落に出し続ける() {
        val s = session(intake = 4)
        val id = ids(s.readTwice("menu item number one")).single()
        s.handle(Event.Partial(id, "メニュー"))
        // 大きく違って読めた（同じ行の番号）
        s.handle(Event.Read(lines("menu itcm numbr 0ne xx"), screen))
        assertEquals("メニュー", s.boxes().single().translation)
    }

    @Test
    fun 映している間は1回しか読めていない行は渡さない() {
        val s = session(intake = 4)
        assertTrue(submitted(s.handle(Event.Read(lines("read once only"), screen))).isEmpty())
        s.handle(Event.Paused)
        s.handle(Event.Resumed)
        // 別の場所に新しく読めた行（1回だけ）
        assertTrue(submitted(s.handle(Event.Read(lines("another line here", x = 600), screen))).isEmpty())
    }

    @Test
    fun 向きが変わったら行を捨てて訳は残す() {
        val s = session()
        val id = ids(s.readTwice("cold brew coffee")).single()
        s.handle(Event.Final(id, "水出しコーヒー"))
        s.handle(Event.Rotated)
        assertTrue(s.boxes().isEmpty())
        s.readTwice("cold brew coffee")
        assertEquals("水出しコーヒー", s.boxes().single().translation)
    }

    @Test
    fun 設定が変わったら訳を捨てて流れを開き直し前の流れの結果は無視する() {
        val s = session()
        val id = ids(s.readTwice("cold brew coffee")).single()
        val effects = s.handle(Event.SettingsChanged(menu.copy(target = Language.EN)))
        assertEquals(Effect.OpenStream(menu.copy(target = Language.EN).options), effects.single())
        // 開くまでは渡さない。前の流れの番号の結果は無視する
        assertTrue(s.handle(Event.Final(id, "古い訳")).isEmpty())
        assertNull(s.boxes().single().translation)
        assertEquals(listOf("cold brew coffee"), submitted(s.handle(Event.StreamOpened)))
    }

    @Test
    fun 一時停止中に原文の言語を変えたら止めた画像を読み直す() {
        val s = session()
        s.readTwice("cold brew coffee")
        s.handle(Event.Paused)
        val effects = s.handle(Event.SettingsChanged(menu.copy(source = Language.EN)))
        assertTrue(effects.contains(Effect.ReadStill))
        assertTrue(s.boxes().isEmpty())
        s.handle(Event.StreamOpened)
        val read = s.handle(Event.Read(lines("cold brew coffee"), null, still = true))
        assertEquals(listOf("cold brew coffee"), submitted(read))
    }

    @Test
    fun 失敗し続ける文は訳せなかったとして終わらせる() {
        val s = session(intake = 1)
        val id = ids(s.readTwice("broken text here")).single()
        // 失敗した直後は同じ文を頼み直さない
        assertTrue(submitted(s.handle(Event.Failed(id))).isEmpty())
        val again = ids(s.handle(Event.Read(lines("broken text here"), screen))).single()
        s.handle(Event.Failed(again))
        assertEquals(CameraSession.UNTRANSLATABLE, s.boxes().single().translation)
        assertTrue(submitted(s.handle(Event.Read(lines("broken text here"), screen))).isEmpty())
    }

    @Test
    fun 途中の訳が出ている似た文は渡さずに出す() {
        val s = session(intake = 4)
        val id = ids(s.handle(Event.Read(lines("menu item number one", "menu item number one", x = 0), screen)).let {
            s.handle(Event.Read(lines("menu item number one"), screen))
        }).single()
        s.handle(Event.Partial(id, "メニュー"))
        assertEquals("メニュー", s.boxes().single().translation)
    }

    @Test
    fun 中央に近い順に読み取りがそろったものを先に同じ文を1つにして選ぶ() {
        val c = { id: Long, t: String, y: Int, settled: Boolean -> TranslationPlan.Candidate(id, t, Box(0, y, 100, y + 10), settled) }
        val chosen = TranslationPlan.choose(
            listOf(
                c(1, "far away text", 0, true), c(2, "near the center", 500, true), c(3, "near the centre", 510, true),
                c(4, "skip this one", 520, true), c(5, "unsure but central", 495, false),
            ),
            screen = Box(0, 0, 100, 1000), free = 3,
        ) { it.text != "skip this one" }
        assertEquals(listOf("near the center", "far away text", "unsure but central"), chosen.map { it.text })
    }
}
