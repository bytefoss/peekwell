@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.bytefoss.peekwell

import android.util.Log
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import android.view.TextureView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player

/**
 * "Night watch": the app's one colour scheme, dark only, since the TV is mostly watched in a dim room and the live
 * picture should be the brightest thing on screen. Every role is generated, none hand-picked, by Material's colour
 * algorithm (material-color-utilities): the dark tonal-spot scheme at standard contrast from a teal-green seed
 * #1FA67A (green means live), with an amber tertiary (hue of #F2A33A, chroma 48) for alerts that aren't a person;
 * a person is error red. To change it, regenerate all of it from new seeds (Material Theme Builder does the same),
 * and update the few copies outside Compose: res/values/colors.xml and res/drawable/banner.xml.
 */
internal val AppColors = darkColorScheme(
    primary = Color(0xFF8CD5B4),
    onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF005139),
    onPrimaryContainer = Color(0xFFA8F2CF),
    inversePrimary = Color(0xFF1E6A4F),
    secondary = Color(0xFFB3CCBE),
    onSecondary = Color(0xFF1F352B),
    secondaryContainer = Color(0xFF354B41),
    onSecondaryContainer = Color(0xFFCFE9D9),
    tertiary = Color(0xFFFFB963),
    onTertiary = Color(0xFF472A00),
    tertiaryContainer = Color(0xFF663E00),
    onTertiaryContainer = Color(0xFFFFDDB9),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDEE4DE),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDEE4DE),
    surfaceVariant = Color(0xFF404944),
    onSurfaceVariant = Color(0xFFBFC9C2),
    surfaceTint = Color(0xFF8CD5B4),
    inverseSurface = Color(0xFFDEE4DE),
    inverseOnSurface = Color(0xFF2C322E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A938D),
    outlineVariant = Color(0xFF404944),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF353B37),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28),
    surfaceContainerHighest = Color(0xFF303632),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainerLowest = Color(0xFF0A0F0C),
    surfaceDim = Color(0xFF0F1512),
    primaryFixed = Color(0xFFA8F2CF),
    primaryFixedDim = Color(0xFF8CD5B4),
    onPrimaryFixed = Color(0xFF002115),
    onPrimaryFixedVariant = Color(0xFF005139),
    secondaryFixed = Color(0xFFCFE9D9),
    secondaryFixedDim = Color(0xFFB3CCBE),
    onSecondaryFixed = Color(0xFF092016),
    onSecondaryFixedVariant = Color(0xFF354B41),
    tertiaryFixed = Color(0xFFFFDDB9),
    tertiaryFixedDim = Color(0xFFFFB963),
    onTertiaryFixed = Color(0xFF2B1700),
    onTertiaryFixedVariant = Color(0xFF663E00),
)

/** Material 3 Expressive: its shapes and type, the app's colour scheme (AppColors), and the expressive (springy) motion. */
@Composable
fun CamTheme(content: @Composable () -> Unit) {
    MaterialExpressiveTheme(colorScheme = AppColors, motionScheme = MotionScheme.expressive()) {
        // No 48 dp touch target round small controls: a TV has no touch screen, and the invisible margin would put
        // the focus ring further from a button above and below than at its sides
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified, content = content)
    }
}

/**
 * Material's focus indicator: a 3 dp outline in the theme's secondary colour, 2 dp outside the control, so it's clear
 * from the sofa what OK will press. Compose draws only a faint focus overlay; the indicator itself is left to apps.
 * Put it first in a control's modifier: it watches focus anywhere inside. isInside draws it just inside the edge, for
 * controls packed too closely for a ring outside.
 */
fun Modifier.focusIndicator(shape: Shape, isInside: Boolean = false): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.secondary
    val separator = MaterialTheme.colorScheme.surfaceContainerLowest
    onFocusChanged { isFocused = it.hasFocus }.drawWithContent {
        drawContent()
        if (!isFocused) return@drawWithContent
        val stroke = 3.dp.toPx()
        if (!isInside) {
            val gap = 2.dp.toPx() + stroke / 2 // outside the control, centred on the stroke
            val outline = shape.createOutline(Size(size.width + gap * 2, size.height + gap * 2), layoutDirection, this)
            translate(-gap, -gap) { drawOutline(outline, color, style = Stroke(stroke)) }
            return@drawWithContent
        }
        // Inside the edge, for controls packed 2 dp apart (segmented lists, connected buttons), where a ring outside
        // would run over the neighbour; a dark line just inside it keeps it visible on a filled, selected control
        val ring = shape.createOutline(Size(size.width - stroke, size.height - stroke), layoutDirection, this)
        translate(stroke / 2, stroke / 2) { drawOutline(ring, color, style = Stroke(stroke)) }
        val lineWidth = 1.5.dp.toPx()
        val lineInset = stroke + lineWidth / 2
        val line = shape.createOutline(Size(size.width - lineInset * 2, size.height - lineInset * 2), layoutDirection, this)
        translate(lineInset, lineInset) { drawOutline(line, separator, style = Stroke(lineWidth)) }
    }
}

/** A status dot and text on a translucent pill over video: "● CAM 1", "● Person". */
@Composable
fun StatusLabel(text: String, dotColor: Color, modifier: Modifier = Modifier, isSmall: Boolean = false, isCompact: Boolean = false) {
    // isCompact: smaller still, for the small pop-up, where the small tag took half the card's width
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)) {
        Row(
            Modifier.padding(
                horizontal = if (isCompact) 7.dp else if (isSmall) 10.dp else 16.dp,
                vertical = if (isCompact) 3.dp else if (isSmall) 5.dp else 8.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(if (isCompact) 6.dp else if (isSmall) 8.dp else 10.dp).background(dotColor, CircleShape))
            Spacer(Modifier.width(if (isCompact) 4.dp else if (isSmall) 6.dp else 8.dp))
            // one line, cut short when it's too long: on the small pop-up a long camera name would wrap into the loader
            Text(
                text,
                style = when {
                    isCompact -> MaterialTheme.typography.labelSmallEmphasized
                    isSmall -> MaterialTheme.typography.labelMediumEmphasized
                    else -> MaterialTheme.typography.labelLargeEmphasized
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A camera's live stream (see Live). surface for full screen (a SurfaceView, the most efficient), else a TextureView,
 * which can sit in rounded, animated cards. Released when it leaves the screen. Until the picture moves, and whenever
 * it stops, a loader says what's happening, so a blank or frozen picture never looks like the app is stuck. Once Live
 * gives up its quick retries it says what's wrong instead, with failedAction under it (how to retry, where the screen
 * has a way to); onFailed tells the caller, and raising retryRequest tries again at once. isPaused holds the picture
 * (see Live.isPaused).
 */
@Composable
fun LiveVideo(
    cam: Cam, hd: Boolean, modifier: Modifier = Modifier, surface: Boolean = false, muted: Boolean = true,
    onPlaying: (Boolean) -> Unit = {}, onFirstFrame: () -> Unit = {},
    retryRequest: Int = 0, onFailed: (Boolean) -> Unit = {}, failedAction: @Composable () -> Unit = {},
    isPaused: Boolean = false,
) {
    key(cam.getStreamUrl(hd)) {
        // what the viewer is waiting for, null while the picture is live; and whether it failed rather than is loading
        var waitingText by remember { mutableStateOf<String?>("Connecting…") }
        var isFailed by remember { mutableStateOf(false) }
        var shownLive by remember { mutableStateOf<Live?>(null) }
        // the picture fades in the first time it arrives, rather than appearing at once; a full screen SurfaceView
        // draws in its own layer, which a fade can't reach
        var hasPicture by remember { mutableStateOf(surface) }
        val pictureAlpha by animateFloatAsState(if (hasPicture) 1f else 0f, MaterialTheme.motionScheme.slowEffectsSpec())
        fun showLive() {
            hasPicture = true
            waitingText = null
            if (isFailed) onFailed(false)
            isFailed = false
        }
        LaunchedEffect(retryRequest) {
            if (retryRequest == 0) return@LaunchedEffect
            shownLive?.retryNow()
            waitingText = "Connecting…"
            if (isFailed) onFailed(false)
            isFailed = false
        }
        LaunchedEffect(isPaused, shownLive) { shownLive?.isPaused = isPaused }
        Box(modifier) {
            AndroidView(
                factory = { context ->
                    val videoView = if (surface) SurfaceView(context) else TextureView(context)
                    val live = Live(context, cam, hd, videoView, muted)
                    shownLive = live
                    live.onStreamLost = { error ->
                        Log.w("peekwell", "${cam.host}: video ${error?.errorCodeName ?: "stopped"}" + if (live.hasGivenUp) ", gave up" else "")
                        Report.noteVideoProblem(cam.host, (error?.errorCodeName ?: "the stream stopped") + if (live.hasGivenUp) ", gave up" else "")
                        // the camera refused the login: RTSP answers 401, somewhere down the causes. Retrying
                        // won't fix that, so it's shown at once
                        val isLoginRefused = generateSequence<Throwable>(error) { it.cause }.any { "401" in it.message.orEmpty() }
                        if (!isLoginRefused && !live.hasGivenUp) {
                            if (waitingText == null) waitingText = "Reconnecting…" // within the quick retries: the loader
                        } else {
                            if (!isFailed) onFailed(true)
                            isFailed = true
                            waitingText = when {
                                isLoginRefused -> "Wrong camera login"
                                error == null -> "The camera stopped sending video"
                                error.errorCode in 2000..2999 -> "Can't reach the camera" // Media3's network errors
                                else -> "The video stopped" // the TV couldn't play what the camera sent
                            }
                        }
                    }
                    live.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying) showLive()
                            onPlaying(isPlaying)
                        }

                        override fun onRenderedFirstFrame() {
                            showLive()
                            onFirstFrame()
                        }

                        // the stream stalled mid-way: Wi-Fi or the camera; Live tries again on its own
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_BUFFERING && waitingText == null) waitingText = "Reconnecting…"
                        }
                    })
                    videoView.tag = live
                    videoView
                },
                onRelease = { (it.tag as Live).release() },
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = pictureAlpha },
            )
            VideoWaiting(waitingText, isFailed, failedAction)
        }
    }
}

/**
 * Over a video that isn't live: while it's connecting, Material 3 Expressive's contained loading indicator; once it
 * has failed, a still "camera off" mark in the error colours instead, since a loader would promise it's about to
 * work, and failedAction under the message. Shown only after half a second, so a stream that starts quickly doesn't
 * flash it.
 */
@Composable
private fun VideoWaiting(waitingText: String?, isFailed: Boolean, failedAction: @Composable () -> Unit) {
    var isShown by remember { mutableStateOf(false) }
    var shownText by remember { mutableStateOf("") } // kept while it fades out
    var isShownFailed by remember { mutableStateOf(false) }
    LaunchedEffect(waitingText, isFailed) {
        if (waitingText == null) {
            isShown = false
            return@LaunchedEffect
        }
        if (!isShown) delay(500)
        shownText = waitingText
        isShownFailed = isFailed
        isShown = true
    }
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        isShown, Modifier.fillMaxSize(),
        enter = fadeIn(motion.defaultEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.8f),
        exit = fadeOut(motion.fastEffectsSpec()),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite }) {
            val isLarge = maxWidth > 600.dp // full screen; tiles and the pop-up are smaller
            // the small pop-up, about 120 dp tall: a smaller loader and text, or they run into its tag
            val isCompact = !isLarge && maxHeight < 160.dp
            // lifted clear of the name tag at the bottom of small views
            Column(
                Modifier.align(Alignment.Center).padding(bottom = if (isLarge) 0.dp else if (isCompact) 30.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val markSize = if (isLarge) 96.dp else if (isCompact) 32.dp else 56.dp
                Crossfade(isShownFailed, animationSpec = motion.defaultEffectsSpec()) { isMarkFailed ->
                    if (isMarkFailed) {
                        Box(
                            Modifier.size(markSize).background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_videocam_off), null, Modifier.size(markSize * 0.5f),
                                MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    } else {
                        ContainedLoadingIndicator(Modifier.size(markSize))
                    }
                }
                Surface(
                    Modifier.padding(top = if (isLarge) 20.dp else if (isCompact) 4.dp else 10.dp), shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f),
                ) {
                    Text(
                        shownText,
                        Modifier.padding(horizontal = if (isCompact) 10.dp else 14.dp, vertical = if (isCompact) 3.dp else 6.dp),
                        style = when {
                            isLarge -> MaterialTheme.typography.titleMediumEmphasized
                            isCompact -> MaterialTheme.typography.labelMediumEmphasized
                            else -> MaterialTheme.typography.labelLargeEmphasized
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center, // a long problem wraps on the small pop-up
                    )
                }
                if (isShownFailed) Box(Modifier.padding(top = if (isLarge) 20.dp else 8.dp)) { failedAction() }
            }
        }
    }
}

/** "[OK] Full screen": a remote button as an outlined key cap, and what it does. */
@Composable
fun KeyHint(key: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Text(key, Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelLargeEmphasized, color = MaterialTheme.colorScheme.onSurface)
        }
        // a soft shadow: readable straight on a bright video, invisible on the app's dark pages
        Text(action, Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.bodyLarge.copy(shadow = Shadow(Color.Black.copy(alpha = 0.8f), blurRadius = 8f)),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
