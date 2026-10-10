package de.powerizzle.powerupgolf

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.powerizzle.powerupgolf.ui.PowerUpGolfApp
import de.powerizzle.powerupgolf.ui.PowerUpGolfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            PowerUpGolfTheme {
                PowerUpGolfApp()
            }
        }
    }
}
