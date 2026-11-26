package app.plamotranslate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.llm.ProcessCompute
import app.plamotranslate.pipeline.compute.ComputeLease
import app.plamotranslate.pipeline.compute.ComputeWork
import app.plamotranslate.pipeline.speech.RecognitionPriority
import kotlinx.coroutines.delay

private const val WINDOW_MILLIS = 12_000L

private data class Kind(val label: String, val color: Color)

// 処理の名前（ComputeWork.name）から、画面の呼び名と色を決める。
private val kinds = mapOf(
    RecognitionPriority.FINAL.name to Kind("確定の書き起こし", Color(0xFFEF5350)),
    RecognitionPriority.PHRASE.name to Kind("文の書き起こし", Color(0xFFFF9800)),
    RecognitionPriority.RETRANSCRIBE.name to Kind("書き起こし直し", Color(0xFFFFCA28)),
    RecognitionPriority.PARTIAL.name to Kind("途中の書き起こし", Color(0xFF42A5F5)),
    RecognitionPriority.PHRASE_LANGUAGE.name to Kind("文の言語の識別", Color(0xFFAB47BC)),
    RecognitionPriority.LANGUAGE.name to Kind("話し中の言語の識別", Color(0xFFE1A6F0)),
    ComputeWork.Translation.name to Kind("翻訳", Color(0xFF66BB6A)),
)
private val trialKind = Kind("試走", Color(0xFFBDBDBD))
private val other = Kind("そのほか", Color(0xFF78909C))

private fun ComputeLease.kind() = if (trial) trialKind else kinds[name] ?: other

/**
 * 計算資源の貸出の様子（詳細設定の「会話中に CPU の使われ方を出す」）。直近12秒の CPU の貸出を処理ごとの色で積み上げ、
 * 上の細い帯に GPU の貸出を描く。白い縦線は、優先する処理に止められて返した所。触れても下の画面の操作を妨げない。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComputeMonitor(modifier: Modifier = Modifier) {
    val activity by ProcessCompute.activity.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.nanoTime() / 1_000_000) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.nanoTime() / 1_000_000
            delay(100)
        }
    }
    val from = now - WINDOW_MILLIS
    val leases = (activity.recent + activity.running).filter { (it.end ?: now) >= from }
    val budget = activity.budget.coerceAtLeast(1)
    val used = activity.running.filter { !it.gpu }.sumOf { it.threads }
    val stopped = leases.count { it.preempted }
    Column(
        modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val waiting = activity.waiting.joinToString("、") { "${it.kind().label} ${it.threads}本" }.ifEmpty { "なし" }
        Text("CPU ${used}/${budget}本　止めた回数（12秒）${stopped}　待ち：$waiting", color = Color.White, fontSize = 11.sp)
        Canvas(Modifier.fillMaxWidth().height((10 + budget * 6).dp)) {
            val width = size.width
            val gpuHeight = 8.dp.toPx()
            val cpuTop = gpuHeight + 2.dp.toPx()
            val row = (size.height - cpuTop) / budget
            fun x(time: Long) = (width * (time - from) / WINDOW_MILLIS).coerceIn(0f, width)
            for (cpu in 1 until budget) {
                drawLine(Color.White.copy(alpha = 0.12f), Offset(0f, cpuTop + row * cpu), Offset(width, cpuTop + row * cpu))
            }
            for (lease in leases.filter { it.gpu }) {
                val left = x(lease.start)
                drawRect(lease.kind().color, Offset(left, 0f), Size(x(lease.end ?: now) - left, gpuHeight))
            }
            // 列ごとに、その時刻に CPU を借りていた貸出を優先度の順に下から積む
            val cpuLeases = leases.filter { !it.gpu }.sortedWith(compareBy<ComputeLease> { it.priority }.thenBy { it.id })
            val step = 2.dp.toPx()
            var left = 0f
            while (left < width) {
                val time = from + (WINDOW_MILLIS * left / width).toLong()
                var bottom = size.height
                for (lease in cpuLeases) {
                    if (time < lease.start || time >= (lease.end ?: now)) continue
                    val height = row * lease.threads
                    drawRect(lease.kind().color, Offset(left, bottom - height), Size(step, height))
                    bottom -= height
                }
                left += step
            }
            for (lease in leases.filter { it.preempted }) {
                val at = x(lease.end!!)
                drawLine(Color.White, Offset(at, cpuTop), Offset(at, size.height), strokeWidth = 1.5.dp.toPx())
            }
        }
        val shown = (leases + activity.waiting).map { it.kind() }.distinct()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (kind in shown) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(kind.color))
                    Text(kind.label, color = Color.White, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}
