@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.bytefoss.peekwell

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

/** One camera full screen with sound. Left/Right switches camera, Back returns to the grid. */
class PlayerActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val cams = Cam.loadCameras(this)
        val start = intent.getIntExtra("i", 0)
        if (start >= cams.size) {
            finish()
            return
        }
        setContent { CamTheme { Player(cams, start) } }
    }
}

@Composable
private fun Player(cams: List<Cam>, start: Int) {
    var index by rememberSaveable { mutableIntStateOf(start) }
    val cam = cams[index]
    // the stream plays only while the screen is started, as the grid's does
    val started by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // the name and what the keys do show for 3 seconds, then fade out: on opening, on switching camera, and on OK
    val label = remember { Animatable(1f) }
    var infoRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(index, infoRequest) {
        label.snapTo(1f)
        delay(3000)
        label.animateTo(0f, tween(500))
    }
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest) // the scheme's near-black, round the video
            .focusRequester(focus).focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter) {
                    if (keyEvent.type == KeyEventType.KeyUp) infoRequest++
                    return@onKeyEvent true
                }
                val step = when (keyEvent.key) {
                    Key.DirectionRight -> 1
                    Key.DirectionLeft -> -1
                    else -> 0
                }
                if (step == 0 || cams.size < 2) return@onKeyEvent false
                if (keyEvent.type == KeyEventType.KeyDown) index = (index + step + cams.size) % cams.size
                true
            },
    ) {
        if (started.isAtLeast(Lifecycle.State.STARTED)) {
            val t0 = remember(index) { SystemClock.elapsedRealtime() }
            // a picture that gave up offers Try again, focused so OK presses it; once it's gone, focus returns here
            // so Left/Right keep switching camera
            var isVideoFailed by remember(index) { mutableStateOf(false) }
            var retryRequest by remember(index) { mutableIntStateOf(0) }
            val retryFocus = remember { FocusRequester() }
            LaunchedEffect(isVideoFailed) { if (!isVideoFailed) runCatching { focus.requestFocus() } }
            LiveVideo(
                cam, hd = true, Modifier.fillMaxSize(), surface = true, muted = false,
                onFirstFrame = { Log.i("peekwell", "${cam.host}: full screen video in ${SystemClock.elapsedRealtime() - t0} ms") },
                retryRequest = retryRequest, onFailed = { isVideoFailed = it },
                failedAction = {
                    Button(
                        onClick = { retryRequest++ }, shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.focusRequester(retryFocus).focusIndicator(CircleShape),
                    ) { Text("Try again") }
                    LaunchedEffect(Unit) { runCatching { retryFocus.requestFocus() } }
                },
            )
        }
        StatusLabel(
            if (cams.size > 1) "‹  ${cam.name}  ·  ${index + 1} of ${cams.size}  ›" else cam.name, MaterialTheme.colorScheme.primary,
            Modifier.align(Alignment.TopStart).padding(48.dp).alpha(label.value),
        )
        // straight on the video, inside the TV's safe area; KeyHint's shadow keeps it readable on a bright picture
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 27.dp).alpha(label.value),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (cams.size > 1) KeyHint("◀ ▶", "Switch camera")
            KeyHint("Back", "Cameras")
        }
    }
}
