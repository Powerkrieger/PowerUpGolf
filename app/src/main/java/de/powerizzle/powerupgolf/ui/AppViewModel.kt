package de.powerizzle.powerupgolf.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import de.powerizzle.powerupgolf.game.BotLevel
import de.powerizzle.powerupgolf.game.PLAYER_COLORS
import de.powerizzle.powerlink.Link
import de.powerizzle.powerlink.PowerLink
import de.powerizzle.powerupgolf.net.LINK_CONFIG
import de.powerizzle.powerupgolf.net.GameClient
import de.powerizzle.powerupgolf.net.GameHost
import de.powerizzle.powerupgolf.net.Session
import kotlinx.coroutines.flow.emptyFlow

enum class Screen { HOME, JOIN, GAME }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("profile", Context.MODE_PRIVATE)

    val powerLink = PowerLink(app, LINK_CONFIG)

    var name by mutableStateOf(prefs.getString("name", "") ?: "")
        private set
    // Pick a random colour once; the default argument must not be evaluated (and saved) on every start.
    var color by mutableLongStateOf(
        if (prefs.contains("color")) prefs.getLong("color", PLAYER_COLORS[0])
        else PLAYER_COLORS.random().also { c -> prefs.edit { putLong("color", c) } }
    )
        private set
    var screen by mutableStateOf(Screen.HOME)
        private set
    var session by mutableStateOf<Session?>(null)
        private set
    /** While hosting: PowerLink's listeners on every medium, for the lobby's "join via" panel. */
    var linkHost by mutableStateOf<PowerLink.LinkHost?>(null)
        private set

    fun updateName(n: String) {
        name = n.take(14)
        prefs.edit { putString("name", name) }
    }

    fun updateColor(c: Long) {
        color = c
        prefs.edit { putLong("color", c) }
    }

    fun host() {
        val h = powerLink.host(name)
        linkHost = h
        session = GameHost(name, color, h.guests, advertise = h::update, onClose = h::close)
        screen = Screen.GAME
    }

    /** A game on this phone only, against computer players. */
    fun playBots() {
        session = GameHost(name, color, emptyFlow()).also { it.addBot(BotLevel.MEDIUM) }
        screen = Screen.GAME
    }

    fun openJoin() {
        screen = Screen.JOIN
    }

    fun joined(link: Link) {
        session = GameClient(link, name, color)
        screen = Screen.GAME
    }

    fun backToMenu() {
        session?.close()
        session = null
        linkHost = null
        screen = Screen.HOME
    }

    override fun onCleared() {
        backToMenu()
    }
}
