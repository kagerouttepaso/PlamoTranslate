package app.plamotranslate

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 機能と操作のアイコン（docs/設計/デザイン言語.md「アイコン」）。24の格子に、太さ2の丸い端の線で描く。
 * 色は持たず、使う側の文字の色で塗る（Icon の tint）。絵文字は端末ごとに絵が変わり、テーマの色にも乗らないので使わない。
 */
object FeatureIcons {
    /** 会話翻訳：向かい合う2つの吹き出し。 */
    val Conversation: ImageVector = lineIcon(
        "Conversation",
        "M5,4 H13 A2,2 0 0 1 15,6 V11 A2,2 0 0 1 13,13 H8 L5,16 V13 A2,2 0 0 1 3,11 V6 A2,2 0 0 1 5,4 Z",
        "M17,9 H19 A2,2 0 0 1 21,11 V16 A2,2 0 0 1 19,18 V21 L16,18 H12 A2,2 0 0 1 10,16 V15.5",
    )

    /** カメラ翻訳：カメラ。 */
    val Camera: ImageVector = lineIcon(
        "Camera",
        "M3,9 A2,2 0 0 1 5,7 H7.5 L9,5 H15 L16.5,7 H19 A2,2 0 0 1 21,9 V18 A2,2 0 0 1 19,20 H5 A2,2 0 0 1 3,18 Z",
        "M15.5,13.5 A3.5,3.5 0 1 1 8.5,13.5 A3.5,3.5 0 1 1 15.5,13.5 Z",
    )

    /** 画面翻訳：文字の並んだ端末の画面。 */
    val Screen: ImageVector = lineIcon(
        "Screen",
        "M8,2 H16 A2,2 0 0 1 18,4 V20 A2,2 0 0 1 16,22 H8 A2,2 0 0 1 6,20 V4 A2,2 0 0 1 8,2 Z",
        "M9.5,7 H14.5",
        "M9.5,10.5 H14.5",
        "M9.5,14 H12.5",
        "M11,18.5 H13",
    )

    /** テキスト翻訳：鉛筆と行。 */
    val Text: ImageVector = lineIcon(
        "Text",
        "M15,4 L19,8 L9,18 L5,19 L6,15 Z",
        "M13,6 L17,10",
        "M13,20 H20",
    )

    /** 設定：歯車。 */
    val Settings: ImageVector = lineIcon(
        "Settings",
        "M15,12 A3,3 0 1 1 9,12 A3,3 0 1 1 15,12 Z",
        "M12,2.5 V5 M12,19 V21.5 M2.5,12 H5 M19,12 H21.5 M5.3,5.3 L7.1,7.1 M16.9,16.9 L18.7,18.7 M5.3,18.7 L7.1,16.9 M16.9,7.1 L18.7,5.3",
        "M18,12 A6,6 0 1 1 6,12 A6,6 0 1 1 18,12 Z",
    )

    private fun lineIcon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
