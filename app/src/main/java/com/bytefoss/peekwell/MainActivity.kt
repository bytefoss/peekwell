@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.bytefoss.peekwell

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/** Every camera live in a two-column grid, plus an Add tile. OK opens a camera full screen, holding OK edits it. */
class MainActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { CamTheme { Grid() } }
    }
}

@Composable
private fun Grid() {
    val ctx = LocalContext.current
    var cams by remember { mutableStateOf(Cam.loadCameras(ctx)) }
    var alerts by remember { mutableStateOf(Cam.isAlertsOn(ctx)) }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    // back from the camera's settings or full screen: reload, and (re)start alerts as the settings say
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        cams = Cam.loadCameras(ctx)
        alerts = Cam.isAlertsOn(ctx)
        overlayOk = Settings.canDrawOverlays(ctx)
        MotionService.syncService(ctx, false)
    }
    // streams only while the grid is on screen: the camera takes few at once, and full screen needs one
    val started by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val playing = started.isAtLeast(Lifecycle.State.STARTED)

    var lastFocus by rememberSaveable { mutableIntStateOf(0) }
    // A picture that gave up may mean its camera got a new address: discovery finds it under its stable ID and the
    // grid reloads. At most every 5 minutes; the alert service does the same while alerts are on
    val scope = rememberCoroutineScope()
    var lastDiscoveryAt by remember { mutableLongStateOf(0L) }
    fun findMovedCameras() {
        val now = SystemClock.elapsedRealtime()
        if (lastDiscoveryAt != 0L && now - lastDiscoveryAt < 5 * 60_000L) return
        lastDiscoveryAt = now
        scope.launch {
            val isMoved = withContext(Dispatchers.IO) { runCatching { Cam.updateFromDiscovery(ctx) }.getOrDefault(false) }
            if (isMoved) {
                cams = Cam.loadCameras(ctx)
                MotionService.syncService(ctx, true)
            }
        }
    }
    var isSettingsFocused by remember { mutableStateOf(false) }
    val focus = remember(cams.size) { List(cams.size + 1) { FocusRequester() } }
    val focusTarget = minOf(lastFocus, focus.size - 1)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 80.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), "Peekwell", Modifier.height(44.dp))
            Spacer(Modifier.weight(1f))
            // the settings for every camera; Up from the top row of tiles reaches it
            FilledTonalButton(
                onClick = { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) },
                modifier = Modifier.focusIndicator(CircleShape).onFocusChanged { isSettingsFocused = it.hasFocus },
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(painterResource(R.drawable.ic_settings), null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Settings")
            }
        }
        // blank (but still a line tall, so the header doesn't jump) until there's a camera to count
        var sub = when (cams.size) { 0 -> ""; 1 -> "1 camera"; else -> "${cams.size} cameras" }
        if (cams.isNotEmpty()) sub += if (alerts) " · Motion alerts on" else " · Motion alerts off"
        if (alerts && cams.isNotEmpty() && !overlayOk) sub += " · Allow Display over other apps for pop-ups"
        Text(
            sub, Modifier.padding(top = 4.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (cams.isEmpty()) {
            // nothing to watch yet: one clear way to start, focused
            val addFocus = remember { FocusRequester() }
            LaunchedEffect(playing) { if (playing) runCatching { addFocus.requestFocus() } }
            Column(
                Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Welcome to Peekwell", style = MaterialTheme.typography.headlineMediumEmphasized,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Find an ONVIF camera on your network, sign in and watch it live here",
                    Modifier.padding(top = 8.dp).widthIn(max = 560.dp), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                )
                Button(
                    onClick = { ctx.startActivity(Intent(ctx, SetupActivity::class.java)) },
                    modifier = Modifier.padding(top = 24.dp).focusRequester(addFocus).focusIndicator(CircleShape)
                        .onFocusChanged { if (it.hasFocus) lastFocus = cams.size },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(painterResource(R.drawable.ic_add), null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Add camera")
                }
            }
        } else {
            LazyVerticalGrid(
                GridCells.Fixed(2),
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
                // room for the focused tile to grow and for its ring: the grid clips what's drawn above and below it
                contentPadding = PaddingValues(bottom = 48.dp, top = 24.dp),
            ) {
                itemsIndexed(cams, key = { i, c -> "$i ${c.host}" }) { i, cam ->
                    // the tile to focus claims it once it's laid out: on opening, and on coming back from a camera
                    LaunchedEffect(playing) { if (playing && i == focusTarget) runCatching { focus[i].requestFocus() } }
                    // a picture that gave up: OK tries it again here rather than opening full screen
                    var isVideoFailed by remember(playing) { mutableStateOf(false) }
                    var retryRequest by remember { mutableIntStateOf(0) }
                    Tile(
                        Modifier.focusRequester(focus[i]).onFocusChanged { if (it.hasFocus) lastFocus = i },
                        onClick = {
                            if (isVideoFailed) retryRequest++
                            else ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("i", i))
                        },
                        onLongClick = { ctx.startActivity(Intent(ctx, SetupActivity::class.java).putExtra("i", i)) },
                    ) {
                        var isLive by remember { mutableStateOf(false) }
                        if (playing) {
                            LiveVideo(
                                cam, hd = false, Modifier.fillMaxSize(), onPlaying = { isLive = it },
                                retryRequest = retryRequest,
                            onFailed = { isFailed ->
                                isVideoFailed = isFailed
                                if (isFailed) findMovedCameras()
                            },
                                failedAction = {
                                    Text(
                                        "Press OK to try again", style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                        StatusLabel(
                            cam.name, if (isLive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            Modifier.align(Alignment.BottomStart).padding(14.dp),
                        )
                        // alerts that are on but not working say so, rather than staying quiet
                        val alertProblem = if (alerts) MotionService.alertProblems[cam.host] else null
                        var shownProblem by remember { mutableStateOf("") } // kept while it fades out
                        LaunchedEffect(alertProblem) { if (alertProblem != null) shownProblem = alertProblem }
                        // named in full: the grid's Column scope would otherwise pick its Column-only version
                        androidx.compose.animation.AnimatedVisibility(
                            alertProblem != null, Modifier.align(Alignment.TopEnd).padding(14.dp),
                            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                        ) {
                            StatusLabel(
                                shownProblem,
                                if (shownProblem == Watcher.STARTING) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error,
                                isSmall = true,
                            )
                        }
                    }
                }
                item {
                    LaunchedEffect(playing) { if (playing && cams.size == focusTarget) runCatching { focus[cams.size].requestFocus() } }
                    Tile(
                        Modifier.focusRequester(focus[cams.size]).onFocusChanged { if (it.hasFocus) lastFocus = cams.size },
                        onClick = { ctx.startActivity(Intent(ctx, SetupActivity::class.java)) }, isOutlined = true,
                    ) {
                        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_add), null, Modifier.size(32.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "Add camera", Modifier.padding(start = 12.dp),
                                style = MaterialTheme.typography.headlineSmallEmphasized,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        // What the remote's buttons do on the focused item, as TV apps show it: at the bottom, off the picture
        val keyHints = when {
            isSettingsFocused -> listOf("OK" to "Open settings")
            lastFocus < cams.size -> listOf("OK" to "Full screen", "Hold OK" to "Edit")
            else -> listOf("OK" to "Add a camera")
        }
        val motion = MaterialTheme.motionScheme
        AnimatedContent(
            keyHints, Modifier.padding(top = 8.dp, bottom = 27.dp), // 27 dp: inside the TV's safe area
            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
        ) { hints ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                for ((key, action) in hints) KeyHint(key, action)
            }
        }
    }
}

/**
 * A 16:9 Material card with the focus indicator, a little larger while focused, as TV launchers do, so it reads from
 * across the room. OK clicks; holding OK long-clicks (Card's own onClick has no long click, so the card takes clicks
 * from combinedClickable). isOutlined for an action, like Add camera, rather than a camera.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    modifier: Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, isOutlined: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.05f else 1f, MaterialTheme.motionScheme.defaultSpatialSpec())
    val cardModifier = modifier.onFocusChanged { isFocused = it.hasFocus }.graphicsLayer { scaleX = scale; scaleY = scale }
        .focusIndicator(shape).fillMaxWidth().aspectRatio(16f / 9f).clip(shape)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    // an action reads as an outline, not as another picture
    if (isOutlined) OutlinedCard(cardModifier, shape = shape) { Box(Modifier.fillMaxSize(), content = content) }
    else ElevatedCard(cardModifier, shape = shape) { Box(Modifier.fillMaxSize(), content = content) }
}
