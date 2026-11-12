package app.plamotranslate.featurelab

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * 専用の描画面（SurfaceView）に動く絵を描く画面（問い2の続き。docs/12_機能テスト.md）。ゲームや動画はこの描画面に描くので、
 * ユーザー補助の窓単位の撮影（takeScreenshotOfWindow）でここが写るかを確かめる。画面いっぱいを緑（0,200,0）で塗り、
 * 白い帯を上下に動かす。窓を撮って緑が写れば、描画面も撮れている。
 */
class SurfaceTestActivity : Activity() {
    @Volatile
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = SurfaceView(this)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                running = true
                Thread {
                    var y = 0
                    while (running) {
                        val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: break
                        // 描画面を返した後は canvas を触れないので、大きさは先に読む
                        val height = canvas.height
                        canvas.drawColor(Color.rgb(0, 200, 0))
                        canvas.drawRect(0f, y.toFloat(), canvas.width.toFloat(), y + 200f, android.graphics.Paint().apply { color = Color.WHITE })
                        runCatching { holder.unlockCanvasAndPost(canvas) }.onFailure { break }
                        y = (y + 40) % height
                        Thread.sleep(16)
                    }
                }.start()
                Lab.log("描画面の試験: 描き始めた")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                running = false
            }
        })
        setContentView(view)
    }
}
