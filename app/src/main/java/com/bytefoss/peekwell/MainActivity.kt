@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)

package com.bytefoss.peekwell

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
    // a moving tile keeps focus, so the grid wouldn't scroll to follow it: each move brings it into view itself
    val inView = remember(cams.size) { List(cams.size) { BringIntoViewRequester() } }
    val focusTarget = minOf(lastFocus, focus.size - 1)

    // Hold OK on a camera shows its actions over its own tile: Move lifts the tile so the arrows slide it through the
    // grid, as moving an app on the TV's home screen works (OK keeps the new order, Back puts it back); Edit; Delete,
    // after asking. focusedAction names the focused one in the hint bar
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var focusedAction by remember { mutableStateOf("") }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var movingIndex by remember { mutableStateOf<Int?>(null) }
    var orderBeforeMove by remember { mutableStateOf<List<Cam>>(emptyList()) }
    fun moveCamera(to: Int) {
        val from = movingIndex ?: return
        if (to !in cams.indices || to == from) return
        cams = cams.toMutableList().apply { add(to, removeAt(from)) }
        movingIndex = to
        lastFocus = to
    }
    fun finishMove(isKept: Boolean) {
        val moved = cams[movingIndex ?: return]
        if (isKept) Cam.saveCameras(ctx, cams) else cams = orderBeforeMove.toMutableList()
        lastFocus = cams.indexOf(moved)
        movingIndex = null
    }
    BackHandler(enabled = movingIndex != null) { finishMove(isKept = false) }
    LaunchedEffect(movingIndex, cams) {
        val moving = movingIndex ?: return@LaunchedEffect
        runCatching { focus[moving].requestFocus() }
        withFrameNanos { } // laid out in its new place first
        runCatching { inView[moving].bringIntoView() }
    }
    fun closeActions() {
        val i = menuIndex ?: return
        menuIndex = null
        runCatching { focus[i].requestFocus() } // back to the tile, rather than lost with the buttons
    }
    deleteIndex?.let { i ->
        ConfirmDialog(
            "Delete ${cams[i].name}?", "Its live view, alerts and login are removed from this TV", "Delete",
            onConfirm = {
                cams = cams.toMutableList().apply { removeAt(i) }
                Cam.saveCameras(ctx, cams)
                MotionService.syncService(ctx, true)
                lastFocus = minOf(i, cams.size) // the camera after it, or Add camera
                deleteIndex = null
            },
            onDismiss = { deleteIndex = null },
        )
    }
    // after a delete the tiles shift: focus goes back to the one now in that place
    LaunchedEffect(cams.size) { runCatching { focus[minOf(lastFocus, focus.size - 1)].requestFocus() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 80.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), "Peekwell", Modifier.height(44.dp))
            Spacer(Modifier.weight(1f))
            // the settings for every camera; Up from the top row of tiles reaches it
            FilledTonalButton(
                onClick = { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) },
                modifier = Modifier.focusProperties { canFocus = movingIndex == null && menuIndex == null }
                    .focusIndicator(CircleShape).onFocusChanged { isSettingsFocused = it.hasFocus },
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
                Modifier.weight(1f).onPreviewKeyEvent { keyEvent ->
                    val moving = movingIndex ?: return@onPreviewKeyEvent false
                    val isDown = keyEvent.type == KeyEventType.KeyDown
                    when (keyEvent.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (!isDown) finishMove(isKept = true)
                        Key.DirectionLeft -> if (isDown && moving % 2 == 1) moveCamera(moving - 1)
                        Key.DirectionRight -> if (isDown && moving % 2 == 0) moveCamera(moving + 1)
                        Key.DirectionUp -> if (isDown) moveCamera(moving - 2)
                        Key.DirectionDown -> if (isDown) moveCamera(moving + 2)
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
                // room for the focused tile to grow and for its ring: the grid clips what's drawn above and below it
                contentPadding = PaddingValues(bottom = 48.dp, top = 24.dp),
            ) {
                itemsIndexed(cams, key = { _, c -> System.identityHashCode(c) }) { i, cam ->
                    // the tile to focus claims it once it's laid out: on opening, and on coming back from a camera
                    LaunchedEffect(playing) { if (playing && i == focusTarget) runCatching { focus[i].requestFocus() } }
                    // a picture that gave up: OK tries it again here rather than opening full screen
                    var isVideoFailed by remember(playing) { mutableStateOf(false) }
                    var retryRequest by remember { mutableIntStateOf(0) }
                    // while one camera is moving or showing its actions, the others are dimmed and out of reach
                    val busyIndex = movingIndex ?: menuIndex
                    Tile(
                        // tiles slide to their new places when one is moved, and close the gap when one is deleted
                        Modifier.animateItem(placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec())
                            .bringIntoViewRequester(inView[i])
                            .focusRequester(focus[i]).focusProperties { canFocus = busyIndex == null || busyIndex == i }
                            .onFocusChanged { if (it.hasFocus) lastFocus = i },
                        onClick = {
                            if (isVideoFailed) retryRequest++
                            else ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("i", i))
                        },
                        onLongClick = { menuIndex = i },
                        isLifted = movingIndex == i, isDimmed = busyIndex != null && busyIndex != i,
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
                        if (menuIndex == i) {
                            TileActions(
                                onFocusAction = { focusedAction = it }, onClose = ::closeActions,
                                onMove = {
                                    menuIndex = null
                                    orderBeforeMove = cams.toList()
                                    movingIndex = i
                                },
                                onEdit = {
                                    menuIndex = null
                                    ctx.startActivity(Intent(ctx, SetupActivity::class.java).putExtra("i", i))
                                },
                                onDelete = {
                                    closeActions()
                                    deleteIndex = i
                                },
                            )
                        }
                    }
                }
                item {
                    LaunchedEffect(playing) { if (playing && cams.size == focusTarget) runCatching { focus[cams.size].requestFocus() } }
                    val isBusy = movingIndex != null || menuIndex != null
                    Tile(
                        Modifier.animateItem(placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec())
                            .focusRequester(focus[cams.size]).focusProperties { canFocus = !isBusy }
                            .onFocusChanged { if (it.hasFocus) lastFocus = cams.size },
                        onClick = { ctx.startActivity(Intent(ctx, SetupActivity::class.java)) }, isOutlined = true,
                        isDimmed = isBusy,
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
            movingIndex != null -> listOf("◀ ▶ ▲ ▼" to "Move", "OK" to "Done", "Back" to "Cancel")
            menuIndex != null -> listOf("◀ ▶" to "Choose", "OK" to focusedAction, "Back" to "Close")
            isSettingsFocused -> listOf("OK" to "Open settings")
            lastFocus < cams.size -> listOf("OK" to "Full screen", "Hold OK" to "Move, edit or delete")
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
 * from combinedClickable). isOutlined for an action, like Add camera, rather than a camera. isLifted while it's being
 * moved, isDimmed while another tile is.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(
    modifier: Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, isOutlined: Boolean = false,
    isLifted: Boolean = false, isDimmed: Boolean = false, content: @Composable BoxScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.extraLarge
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        when {
            isLifted -> 1.1f
            isFocused -> 1.05f
            else -> 1f
        },
        MaterialTheme.motionScheme.defaultSpatialSpec(),
    )
    val alpha by animateFloatAsState(if (isDimmed) 0.4f else 1f, MaterialTheme.motionScheme.defaultEffectsSpec())
    val cardModifier = modifier.onFocusChanged { isFocused = it.hasFocus }
        .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
        .focusIndicator(shape).fillMaxWidth().aspectRatio(16f / 9f).clip(shape)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    // an action reads as an outline, not as another picture
    if (isOutlined) OutlinedCard(cardModifier, shape = shape) { Box(Modifier.fillMaxSize(), content = content) }
    else ElevatedCard(cardModifier, shape = shape) { Box(Modifier.fillMaxSize(), content = content) }
}

/**
 * A camera's actions over its own tile, shown by holding OK on it: Move, Edit and Delete as icon buttons on a dimmed
 * picture. Focus stays on them until one is chosen or Back closes them. The hold goes on sending OK until it's
 * released, which would press the first button, so OK only counts once it's pressed afresh here.
 */
@Composable
private fun BoxScope.TileActions(
    onFocusAction: (String) -> Unit, onClose: () -> Unit, onMove: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
) {
    var isOkPressedHere by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)))
    val actions = listOf(
        Triple("Move", R.drawable.ic_move, onMove), Triple("Edit", R.drawable.ic_edit, onEdit),
        Triple("Delete", R.drawable.ic_delete, onDelete),
    )
    Row(
        Modifier.align(Alignment.Center).onPreviewKeyEvent { keyEvent ->
            // Back closes them here: left to Compose, the first Back would only take focus off the buttons
            if (keyEvent.key == Key.Back) {
                if (keyEvent.type == KeyEventType.KeyUp) onClose()
                return@onPreviewKeyEvent true
            }
            val isOk = keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter
            if (!isOk) return@onPreviewKeyEvent false
            // a button would take the hold's repeated presses as a press and click on its release, so every OK is
            // swallowed until a fresh press (repeat count 0) made here
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.nativeKeyEvent.repeatCount == 0) isOkPressedHere = true
            !isOkPressedHere
        },
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        actions.forEachIndexed { k, (label, icon, onClick) ->
            val isDelete = label == "Delete"
            FilledTonalIconButton(
                onClick = onClick,
                modifier = (if (k == 0) Modifier.focusRequester(firstFocus) else Modifier).size(72.dp)
                    .focusProperties {
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                        if (k == 0) left = FocusRequester.Cancel
                        if (k == actions.lastIndex) right = FocusRequester.Cancel
                    }
                    .onFocusChanged { if (it.isFocused) onFocusAction(label) }.focusIndicator(CircleShape),
                colors = if (isDelete) IconButtonDefaults.filledTonalIconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                else IconButtonDefaults.filledTonalIconButtonColors(),
            ) { Icon(painterResource(icon), label, Modifier.size(32.dp)) }
        }
    }
}
