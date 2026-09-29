package com.veldash

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/**
 * PLACEHOLDER for Step 2 build verification. Replaced in Step 3 with the MapLibre MapView host.
 *
 * Plain android.app.Activity: no AppCompat, no Fragment, no ViewModel, no Lifecycle.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(View(this))
    }
}
