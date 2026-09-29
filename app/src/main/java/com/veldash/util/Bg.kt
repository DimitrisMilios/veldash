package com.veldash.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * The only threading primitive in the app: one low-priority background thread plus the
 * main-thread Handler. No coroutines, no RxJava, no WorkManager.
 *
 * Disk scans, .mbtiles metadata reads, routing requests and offline route computation
 * all go through here, serialised. On a low-end multi-core SoC that is the point:
 * one extra core busy, the rest free for the GL render thread and the UI thread.
 */
object Bg {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "veldash-bg").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /** Fire-and-forget on the background thread. */
    fun execute(task: () -> Unit) {
        executor.execute(task)
    }

    /** Compute [task] in the background, deliver its result on the main thread. */
    fun <T> compute(task: () -> T, onResult: (T) -> Unit) {
        executor.execute {
            val result = task()
            main.post { onResult(result) }
        }
    }

    /** Post to the main thread. */
    fun post(task: () -> Unit) {
        main.post(task)
    }
}
