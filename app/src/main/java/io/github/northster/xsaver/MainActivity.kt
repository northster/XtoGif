package io.github.northster.xsaver

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.northster.xsaver.ui.ConvertScreen
import io.github.northster.xsaver.ui.MainScreen
import io.github.northster.xsaver.ui.MainViewModel
import io.github.northster.xsaver.ui.Screen
import io.github.northster.xsaver.ui.SettingsScreen
import io.github.northster.xsaver.ui.XSaverTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            XSaverTheme {
                val screen by vm.screen.collectAsStateWithLifecycle()
                BackHandler(enabled = screen != Screen.MAIN) { vm.back() }
                when (screen) {
                    Screen.MAIN -> MainScreen(vm)
                    Screen.CONVERT -> ConvertScreen(vm)
                    Screen.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val type = intent.type.orEmpty()
        if (type.startsWith("video/") || type == "image/gif") {
            streamOf(intent)?.let { vm.convertUri(it) }
        } else {
            val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString(" ")
            if (text.isNotBlank()) vm.openLink(text)
        }
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
}
