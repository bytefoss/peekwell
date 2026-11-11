package com.bytefoss.peekwell

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay

/**
 * The alert popups, in the corner chosen in Settings (bottom right by default) over whatever is on screen: one card per camera that's reporting, each
 * with that camera's small stream, muted. A card stays while its camera keeps reporting, and 20 seconds after the
 * last report. At most two show at once: each decodes its own live video, and TVs have few decoders. A third camera
 * replaces the card that has been quiet longest. They never take the remote's keys, so the app underneath keeps
 * working. Cards slide in from the nearest side edge and back out with the theme's expressive motion; all live in one window.
 */
class Overlay(private val ctx: Context) {
    private val windowManager = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val cards = mutableStateListOf<AlertCard>() // showing or sliding out, oldest at the top
    private var window: ComposeView? = null
    private var owner: WindowOwner? = null

    /** One camera's card: what it's labelled, when its camera last reported, and when it started sliding out. */
    private inner class AlertCard(val cam: Cam, kind: String) {
        val kind = mutableStateOf(kind)
        val visible = MutableTransitionState(false).apply { targetState = true } // slides in once composed
        val openedAt = SystemClock.elapsedRealtime()
        var reportedAt by mutableLongStateOf(openedAt) // the time-left line refills on each report
        var closingAt = 0L
        val linger = Runnable { closeCard(this) }
    }

    /** Shows or keeps up this camera's card, labelled kind ("Person", "Motion", "Line crossing", ...). */
    fun show(cam: Cam, kind: String) {
        if (!Settings.canDrawOverlays(ctx)) return
        val showing = cards.filter { it.visible.targetState }
        var card = showing.firstOrNull { it.cam.host == cam.host }
        if (card != null) {
            card.kind.value = kind
            card.reportedAt = SystemClock.elapsedRealtime()
        } else {
            if (showing.size >= MAX_CARDS) closeCard(showing.minBy { it.reportedAt })
            card = AlertCard(cam, kind)
            cards += card
            openWindow()
        }
        handler.removeCallbacks(card.linger)
        handler.postDelayed(card.linger, LINGER_MS)
    }

    /** Takes every card down: sliding out when animate, at once otherwise (the service is ending, or a test alert replaces them). */
    fun hide(animate: Boolean) {
        if (animate) {
            cards.filter { it.visible.targetState }.forEach(::closeCard)
            return
        }
        cards.forEach { handler.removeCallbacks(it.linger) }
        cards.clear()
        removeWindow()
    }

    /** Starts a card sliding out; it's dropped, and the window with the last one, once it's gone from view. */
    private fun closeCard(card: AlertCard) {
        handler.removeCallbacks(card.linger)
        card.closingAt = SystemClock.elapsedRealtime()
        card.visible.targetState = false
    }

    private fun openWindow() {
        if (window != null) return
        val displayMetrics = ctx.resources.displayMetrics
        val alertSize = Cam.getAlertSize(ctx)
        val widthPercent = when (alertSize) { "small" -> 22; "large" -> 40; else -> 30 }
        val cardWidth = displayMetrics.widthPixels * widthPercent / 100
        val cardHeight = cardWidth * 9 / 16
        // Android TV's safe area: 5% in from each edge, as some TVs crop the picture (overscan)
        val margin = displayMetrics.widthPixels / 20
        val verticalMargin = displayMetrics.heightPixels / 20
        val isNamed = Cam.loadCameras(ctx).size > 1 // the camera's name only helps with more than one
        val isLabelShown = Cam.isLabelShown(ctx)
        val isTimerShown = Cam.isTimerShown(ctx)
        val position = Cam.getAlertPosition(ctx)
        val isTop = position.startsWith("top")
        val isLeft = position.endsWith("left")
        val windowOwner = WindowOwner()
        val view = ComposeView(ctx)
        view.setViewTreeLifecycleOwner(windowOwner)
        view.setViewTreeSavedStateRegistryOwner(windowOwner)
        view.setContent {
            CamTheme {
                val density = LocalDensity.current
                val motion = MaterialTheme.motionScheme
                // The window reaches the screen's side edge, so a card slides in from beyond it uncut
                val edgePadding = with(density) { margin.toDp() }
                val fromEdge = { width: Int -> if (isLeft) -(width + margin) else width + margin }
                Column(
                    Modifier.fillMaxSize().padding(start = if (isLeft) edgePadding else 0.dp, end = if (isLeft) 0.dp else edgePadding),
                    verticalArrangement = Arrangement.spacedBy(16.dp, if (isTop) Alignment.Top else Alignment.Bottom),
                    horizontalAlignment = if (isLeft) Alignment.Start else Alignment.End,
                ) {
                    for (card in cards) key(card) {
                        AnimatedVisibility(
                            card.visible,
                            enter = slideInHorizontally(motion.defaultSpatialSpec(), fromEdge) +
                                    expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
                            // the other card moves into the gap as this one goes
                            exit = slideOutHorizontally(motion.fastSpatialSpec(), fromEdge) +
                                    shrinkVertically(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                        ) {
                            ElevatedCard(
                                Modifier.size(with(density) { cardWidth.toDp() }, with(density) { cardHeight.toDp() }),
                                shape = MaterialTheme.shapes.large,
                            ) {
                                Box(Modifier.fillMaxSize()) {
                                    // how long from the alert to a live picture: the number to watch when tuning for speed
                                    LiveVideo(card.cam, hd = false, Modifier.fillMaxSize(), onFirstFrame = {
                                        Log.i("peekwell", "${card.cam.host}: popup video in ${SystemClock.elapsedRealtime() - card.openedAt} ms")
                                    })
                                    if (isTimerShown) {
                                        // the time left before it closes, as a thin line along the bottom edge (the card's
                                        // rounded corners clip it). While someone stays in view the camera reports about
                                        // every second and the card stays up: the line holds still, full. It runs out only
                                        // once the reports stop, ending as the card slides away; a report meanwhile refills it
                                        val timeLeft = remember { Animatable(1f) }
                                        val refillSpec = motion.fastSpatialSpec<Float>()
                                        LaunchedEffect(card.reportedAt) {
                                            timeLeft.animateTo(1f, refillSpec)
                                            delay(QUIET_MS)
                                            val remainingMs = LINGER_MS - (SystemClock.elapsedRealtime() - card.reportedAt)
                                            timeLeft.animateTo(0f, tween(remainingMs.coerceAtLeast(0L).toInt(), easing = LinearEasing))
                                        }
                                        // in the app's accent, the same for every detection: what was detected is the tag's to say
                                        Box(
                                            Modifier.align(Alignment.BottomStart).fillMaxWidth(timeLeft.value.coerceIn(0f, 1f))
                                                .height(3.dp).background(MaterialTheme.colorScheme.primary),
                                        )
                                    }
                                    // A small label at the bottom: the picture is small, and many cameras stamp the
                                    // date and time at the top left
                                    if (isLabelShown) {
                                        // "Motion" turning into "Person" crossfades rather than jumping
                                        AnimatedContent(
                                            card.kind.value,
                                            Modifier.align(Alignment.BottomStart).padding(with(density) { (cardHeight / 24).toDp() }),
                                            transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
                                            contentAlignment = Alignment.BottomStart,
                                        ) { shownKind ->
                                            StatusLabel(
                                                if (isNamed) "$shownKind · ${card.cam.name}" else shownKind,
                                                if (shownKind == "Person") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                                                isSmall = true, isCompact = alertSize == "small",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // gone from view after sliding out: drop it, and take the window down with the last one
                        LaunchedEffect(card.visible.isIdle, card.visible.currentState) {
                            if (card.visible.isIdle && !card.visible.currentState && card.closingAt > 0) {
                                cards.remove(card)
                                Log.i("peekwell", "popup closed, slid out in ${SystemClock.elapsedRealtime() - card.closingAt} ms")
                                if (cards.isEmpty()) removeWindow()
                            }
                        }
                    }
                }
            }
        }
        // hardware acceleration is not on by default for a window that no activity owns, and video needs it
        val windowParams = WindowManager.LayoutParams(
            cardWidth + margin, MAX_CARDS * cardHeight + with(ctx.resources.displayMetrics) { (16 * density).toInt() },
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (isTop) Gravity.TOP else Gravity.BOTTOM) or (if (isLeft) Gravity.START else Gravity.END)
            x = 0
            y = verticalMargin
        }
        windowOwner.start()
        windowManager.addView(view, windowParams)
        window = view
        owner = windowOwner
    }

    private fun removeWindow() {
        val view = window ?: return
        window = null
        if (view.isAttachedToWindow) windowManager.removeView(view) // disposes the composition, releasing the videos
        owner?.stop()
        owner = null
    }

    /** A lifecycle for a window no activity owns, so Compose can run in it: resumed while shown. */
    private class WindowOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        fun start() {
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    companion object {
        private const val LINGER_MS = 20_000L
        private const val QUIET_MS = 3_000L // no report for this long: whatever was in view has left, the line starts to run out
        private const val MAX_CARDS = 2
    }
}
