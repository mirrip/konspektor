package app.konspektor

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.konspektor.ui.AppRoot
import app.konspektor.ui.UiSignals
import app.konspektor.ui.theme.KonspektorTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        takeKeyFromIntent(intent)
        setContent {
            KonspektorTheme { AppRoot() }
        }
    }

    override fun onResume() {
        super.onResume()
        UiSignals.resumes.intValue++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeKeyFromIntent(intent)
    }

    /** Lets the key be set from a computer: adb shell am start -n app.konspektor/.MainActivity --es groq_key gsk_... */
    private fun takeKeyFromIntent(intent: Intent?) {
        val key = intent?.getStringExtra("groq_key")?.trim() ?: return
        if (key.startsWith("gsk_")) {
            Prefs(this).groqKey = key
            Toast.makeText(this, "Ключ Groq сохранён", Toast.LENGTH_SHORT).show()
            UiSignals.edited()
        }
    }
}
