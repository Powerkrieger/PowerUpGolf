package de.powerizzle.powerupgolf.ui

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import de.powerizzle.powerupgolf.game.PLAYER_COLORS
import de.powerizzle.powerupgolf.net.Discovery
import de.powerizzle.powerupgolf.net.GameClient
import de.powerizzle.powerupgolf.net.GameHost
import de.powerizzle.powerupgolf.net.Session

enum class Screen { HOME, JOIN, GAME }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("profile", Context.MODE_PRIVATE)

    // Many phones drop broadcast packets to save battery unless an app holds this lock.
    private val multicastLock = (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("powerupgolf").apply { setReferenceCounted(false) }

    var name by mutableStateOf(prefs.getString("name", "") ?: "")
        private set
    var color by mutableLongStateOf(prefs.getLong("color", PLAYER_COLORS.random().also { c -> prefs.edit { putLong("color", c) } }))
        private set
    var screen by mutableStateOf(Screen.HOME)
        private set
    var session by mutableStateOf<Session?>(null)
        private set

    val foundHosts = Discovery.scan()

    fun updateName(n: String) {
        name = n.take(14)
        prefs.edit { putString("name", name) }
    }

    fun updateColor(c: Long) {
        color = c
        prefs.edit { putLong("color", c) }
    }

    fun host() {
        multicastLock.acquire()
        session = GameHost(name, color)
        screen = Screen.GAME
    }

    fun openJoin() {
        multicastLock.acquire()
        screen = Screen.JOIN
    }

    fun join(address: String) {
        session = GameClient(address, name, color)
        screen = Screen.GAME
    }

    fun backToMenu() {
        session?.close()
        session = null
        if (multicastLock.isHeld) multicastLock.release()
        screen = Screen.HOME
    }

    override fun onCleared() {
        backToMenu()
    }
}
