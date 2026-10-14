package com.brandonwallace.starshot

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brandonwallace.starshot.ui.StarShotApp
import com.brandonwallace.starshot.ui.StarShotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { StarShotTheme { StarShotApp(this) } }
    }

    /** -1 = system brightness; ~0 = as dark as possible so the screen doesn't light the scene. */
    fun setBrightness(v: Float) {
        val a = window.attributes
        if (a.screenBrightness != v) { a.screenBrightness = v; window.attributes = a }
    }
}
