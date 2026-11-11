@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)

package com.bytefoss.peekwell

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.KeyEvent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** Add a camera (no "i" extra) or edit the one at index "i". */
class SetupActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val cams = Cam.loadCameras(this)
        val index = intent.getIntExtra("i", -1)
        setContent { CamTheme { if (index in cams.indices) Setup(this, cams, index) else AddCamera(this, cams) } }
    }

    // Holding OK on a camera opens this screen while OK is still down: the rest of that press, its repeats and its
    // release, mustn't press whatever has focus here (it switched a detection chip off). A fresh press works as usual
    private var isOkHeldFromLaunch = true

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isOk = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isOk && isOkHeldFromLaunch) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                isOkHeldFromLaunch = false
            } else {
                if (event.action == KeyEvent.ACTION_UP) isOkHeldFromLaunch = false
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

/** The settings for every camera: alerts on/off, the pop-up's tag, sound, quiet hours. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            CamTheme {
                var status by remember { mutableStateOf("") }
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp),
                ) {
                    // The version sits beside the title: a plain text at the bottom can't be focused, so the remote
                    // could never scroll down to it
                    Row(Modifier.widthIn(max = 720.dp), verticalAlignment = Alignment.CenterVertically) { // ends where the lists do
                        Text("Settings", style = MaterialTheme.typography.headlineLargeEmphasized, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.weight(1f))
                        Text("v" + packageManager.getPackageInfo(packageName, 0).versionName,
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AlertSettings(this@SettingsActivity) { askOverlayPermission(this@SettingsActivity)?.let { status = it } }
                    if (status.isNotEmpty()) {
                        Text(status, Modifier.padding(top = 12.dp).widthIn(max = TEXT_WIDTH), style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Heading("Help")
                    var isSourceShown by remember { mutableStateOf(false) }
                    SegmentedGroup {
                        listOf(
                            Triple("Report a problem", "Checks your cameras and shows a report you can send from your phone") {
                                startActivity(Intent(this@SettingsActivity, ReportActivity::class.java))
                            },
                            Triple("Source code", "Peekwell is open source: ${SOURCE_URL.removePrefix("https://")}") { isSourceShown = true },
                            Triple("Open source licenses", "The libraries Peekwell is built with") {
                                startActivity(Intent(this@SettingsActivity, LicensesActivity::class.java))
                            },
                        ).forEachIndexed { i, (title, description, onClick) ->
                            val shapes = ListItemDefaults.segmentedShapes(i, 3)
                            SegmentedListItem(
                                onClick = onClick, shapes = shapes,
                                modifier = Modifier.focusIndicator(shapes.focusedShape, isInside = true),
                                supportingContent = { Text(description) },
                            ) { Text(title) }
                        }
                    }
                    if (isSourceShown) SourceCodeDialog { isSourceShown = false }
                }
            }
        }
    }
}

/** Where the source lives, as a QR code: most TVs have no browser to open a link in. */
@Composable
private fun SourceCodeDialog(onDismiss: () -> Unit) {
    val closeFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Source code") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                val qrCode = remember { Report.makeQrCode(SOURCE_URL, 400).asImageBitmap() }
                Image(qrCode, "QR code that opens Peekwell's source code", Modifier.size(200.dp))
                Text("Scan with your phone to see Peekwell's code on GitHub, or go to\n${SOURCE_URL.removePrefix("https://")}\n\n" +
                    "It's free and open source under the Apache License 2.0.")
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.focusRequester(closeFocus).focusIndicator(CircleShape)) {
                Text("Close")
            }
        },
    )
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
}

/** Open source licenses: the libraries in the app and the license they're under (all Apache 2.0, like the app). */
class LicensesActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val licenseText = resources.openRawResource(R.raw.apache_license).bufferedReader().use { it.readText() }
        setContent {
            CamTheme {
                val backFocus = remember { FocusRequester() }
                val textFocus = remember { FocusRequester() }
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 96.dp, vertical = 40.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Open source licenses", Modifier.weight(1f), style = MaterialTheme.typography.headlineLargeEmphasized,
                            color = MaterialTheme.colorScheme.onSurface)
                        Action("Back", modifier = Modifier.focusRequester(backFocus).focusProperties { down = textFocus }) { finish() }
                    }
                    ScrollingText(
                        LIBRARIES + "\n\n" + licenseText,
                        Modifier.padding(top = 16.dp).focusRequester(textFocus),
                        MaterialTheme.typography.bodyMedium,
                    )
                }
                LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }
            }
        }
    }

    companion object {
        // every library in the release build (gradlew :app:dependencies); check again when one is added
        private const val LIBRARIES = """Peekwell is open source under the Apache License 2.0, and is built with these open source libraries, all under the Apache License 2.0 too:

• Android Jetpack (AndroidX): Compose, Material 3, Activity, Lifecycle, Core and more, by The Android Open Source Project
• Media3 ExoPlayer, with RTSP, by The Android Open Source Project
• Guava, by Google
• Kotlin standard library, kotlinx.coroutines and kotlinx.serialization, by JetBrains
• JetBrains Java Annotations, by JetBrains
• JSpecify, by the JSpecify authors
• ZXing, by the ZXing authors

The license:"""
    }
}

/**
 * Text longer than the screen that the remote scrolls: it takes focus, then Down and Up scroll it, and Up at its top
 * moves focus on up.
 */
@Composable
private fun ScrollingText(text: String, modifier: Modifier, style: TextStyle) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    Text(
        text,
        modifier.focusIndicator(MaterialTheme.shapes.small)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = when (keyEvent.key) {
                    Key.DirectionDown -> 240f
                    Key.DirectionUp -> if (scroll.value == 0) return@onPreviewKeyEvent false else -240f
                    else -> return@onPreviewKeyEvent false
                }
                scope.launch { scroll.animateScrollBy(step) }
                true
            }
            .focusable().verticalScroll(scroll).padding(12.dp),
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Report a problem: checks every camera, then shows the report beside a QR code that opens it as a GitHub issue on
 * the user's phone. Nothing leaves the TV unless they scan it and send it.
 */
class ReportActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent { CamTheme { ProblemReport(this) } }
    }
}

@Composable
private fun ProblemReport(ctx: ComponentActivity) {
    val cams = remember { Cam.loadCameras(ctx) }
    var progress by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<String?>(null) }
    var checkRequest by remember { mutableIntStateOf(0) }
    val againFocus = remember { FocusRequester() }
    val reportFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() } // while the checks run, the one thing to press
    val scope = rememberCoroutineScope()
    LaunchedEffect(checkRequest) {
        report = null
        val sections = cams.mapIndexed { i, cam ->
            progress = "Checking ${cam.name} (${i + 1} of ${cams.size})…"
            val lines = withContext(Dispatchers.IO) { Report.checkOnvif(cam) }
            progress = "Testing ${cam.name}'s video…"
            val overTcp = Report.testVideo(ctx, cam, isOverTcp = true)
            // only when TCP gave no picture: whether the camera sends video the other way
            val overUdp = if (overTcp.startsWith("picture")) "not needed" else Report.testVideo(ctx, cam, isOverTcp = false)
            cam to lines + listOf(
                "Video over TCP: $overTcp", "Video over UDP: $overUdp",
                "Last video problem: ${Report.getLastVideoProblem(cam.host)}",
                "Alerts: ${MotionService.alertProblems[cam.host] ?: "no problem shown"}",
            )
        }
        report = Report.buildReport(Report.describeTv(ctx, cams), sections)
    }
    LaunchedEffect(report) { runCatching { (if (report != null) againFocus else backFocus).requestFocus() } }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 96.dp, vertical = 40.dp),
    ) {
        val shownReport = report
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Report a problem", Modifier.weight(1f), style = MaterialTheme.typography.headlineLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (shownReport != null) {
                    Action("Check again", modifier = Modifier.focusRequester(againFocus).focusProperties { down = reportFocus }) {
                        checkRequest++
                    }
                }
                Action("Back", modifier = Modifier.focusRequester(backFocus).focusProperties { if (shownReport != null) down = reportFocus }) {
                    ctx.finish()
                }
            }
        }
        if (shownReport == null) {
            StatusRow(progress.ifEmpty { "Checking your cameras…" }, isProblem = false, isWaiting = true)
            return@Column
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            // Up at the report's top goes on to Check again
            ScrollingText(shownReport, Modifier.weight(1f).focusRequester(reportFocus),
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            Column(Modifier.width(300.dp)) {
                val qrCode = remember(shownReport) { Report.makeQrCode(Report.buildIssueUrl(shownReport), 600).asImageBitmap() }
                Image(qrCode, "QR code that opens this report as a GitHub issue", Modifier.size(300.dp))
                Text(
                    "Scan with your phone to send this report as a GitHub issue. Nothing is sent from the TV, and the " +
                        "report has no passwords or full addresses.",
                    Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Edit a saved camera: its name, address, login and which of its detections alert; Test, Test alert, Delete. */
@Composable
private fun Setup(activity: ComponentActivity, cams: MutableList<Cam>, index: Int) {
    val ctx = activity
    val scope = rememberCoroutineScope()
    val cam = cams[index]

    val nameField = rememberTextFieldState(cam.name)
    val hostField = rememberTextFieldState(cam.host)
    val userField = rememberTextFieldState(cam.user)
    val passField = rememberTextFieldState(cam.pass)
    val kindsSwitchedOff = remember { mutableStateListOf<String>().apply { addAll(cam.kindsOff) } }
    var isDeleteAsked by remember { mutableStateOf(false) }
    var isDiscardAsked by remember { mutableStateOf(false) }
    // anything typed or chosen that Save would keep
    val isChanged = nameField.text.toString().trim() != cam.name || hostField.text.toString().trim() != cam.host ||
            userField.text.toString().trim() != cam.user || passField.text.toString() != cam.pass ||
            kindsSwitchedOff.toSet() != cam.kindsOff
    var status by remember { mutableStateOf("") }
    var isProblem by remember { mutableStateOf(false) } // status is something to fix: shown in the error colour
    // testing or saving: the loader shows, and presses meanwhile are ignored rather than starting a second one
    var isWaiting by remember { mutableStateOf(false) }
    var isPasswordShown by remember { mutableStateOf(false) }
    val saveFocus = remember { FocusRequester() }

    fun showStatus(text: String, problem: Boolean = false) {
        status = text
        isProblem = problem
    }

    fun readCameraFromFields() = Cam().apply {
        name = nameField.text.toString().trim()
        host = hostField.text.toString().trim()
        user = userField.text.toString().trim()
        pass = passField.text.toString()
        if (name.isEmpty()) name = host
        kindsOff = HashSet(kindsSwitchedOff)
        deviceId = cam.deviceId // the same camera, wherever its address is
    }

    fun testLogin() {
        if (isWaiting) return
        val camera = readCameraFromFields()
        showStatus("Connecting to ${camera.host.ifEmpty { "the camera" }}…")
        isWaiting = true
        scope.launch {
            val model = withContext(Dispatchers.IO) {
                runCatching { connectCamera(camera).getModel() }
            }
            isWaiting = false
            model.onSuccess { showStatus("Connected to $it") }
                .onFailure { showStatus(describeConnectError(it, camera.host), problem = true) }
        }
    }

    /** Shows this camera's pop-up and announcement now, through the same path a real detection takes. */
    fun sendTestAlert() {
        if (!Cam.isAlertsOn(ctx)) {
            showStatus("Turn on motion alerts in Settings first", problem = true)
            return
        }
        if (!Settings.canDrawOverlays(ctx)) askOverlayPermission(ctx)?.let { showStatus(it, problem = true) }
        ctx.startForegroundService(Intent(ctx, MotionService::class.java).putExtra("test", index))
        showStatus("Press Home to see the test alert over other apps")
    }

    fun saveAndClose() {
        Cam.saveCameras(ctx, cams)
        MotionService.syncService(ctx, true)
        activity.finish()
    }

    /** Saves the camera with where its events and video are, asked from the camera itself. */
    fun saveCamera() {
        if (isWaiting) return
        val camera = readCameraFromFields()
        if (camera.host.isEmpty() || camera.user.isEmpty() || camera.pass.isEmpty()) {
            showStatus("Enter the address, username and password", problem = true)
            return
        }
        isWaiting = true
        showStatus("Saving…")
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val onvif = connectCamera(camera)
                    onvif.findServiceAddresses()
                    if (camera.deviceId.isEmpty()) camera.deviceId = runCatching { Onvif.findDeviceId(camera.host) }.getOrDefault("")
                    // what it can detect; a camera that won't say keeps what it offered before, or every kind we know
                    camera.kinds = try {
                        onvif.listEventKinds()
                    } catch (e: IOException) {
                        Log.w("peekwell", "${camera.host}: couldn't ask what it can detect (${e.message})")
                        if (cam.host == camera.host) cam.kinds else emptyList()
                    }
                } catch (e: IOException) {
                    // camera off or not answering: saved anyway, with what it said before if it's at the same address
                    Log.w("peekwell", "${camera.host}: couldn't ask where its events and video are (${e.message})")
                    if (cam.host == camera.host) {
                        camera.eventsUrl = cam.eventsUrl
                        camera.streamUrlHd = cam.streamUrlHd
                        camera.streamUrlSd = cam.streamUrlSd
                        camera.kinds = cam.kinds
                    }
                }
            }
            cams[index] = camera
            saveAndClose()
        }
    }

    fun deleteCamera() {
        cams.removeAt(index)
        saveAndClose()
    }

    // the page on a container tone: Material colours segmented rows with surface, so they stand out from it
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).verticalScroll(rememberScrollState())
            .padding(horizontal = 96.dp, vertical = 40.dp),
    ) {
        Text("Edit camera", style = MaterialTheme.typography.headlineLargeEmphasized, color = MaterialTheme.colorScheme.onSurface)
        Hint(LOGIN_HINT)
        Field("Name", "Front door", nameField)
        Field("Camera address", ADDRESS_EXAMPLE, hostField, keyboard = KeyboardType.Uri)
        Field("Username", "", userField)
        Field(
            "Password", "", passField, keyboard = KeyboardType.Password, isSecret = true, isRevealed = isPasswordShown,
            onDone = { runCatching { saveFocus.requestFocus() } },
        )
        ShowPasswordChip(isPasswordShown) { isPasswordShown = it }
        // which of this camera's detections alert on the TV: what it said it can detect when it was saved
        Heading("Alert on the TV for")
        KindChips(cam.listKinds(), kindsSwitchedOff)
        Hint(
            if (cam.kinds.isEmpty()) "Save to see what this camera can detect"
            else "Only the ones turned on alert on this TV. When several happen together, the most important is shown."
        )
        FlowRow(
            Modifier.padding(top = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Action("Save", isPrimary = true, Modifier.focusRequester(saveFocus)) { saveCamera() }
            Action("Test") { testLogin() }
            Action("Test alert") { sendTestAlert() }
            OutlinedButton(
                onClick = { isDeleteAsked = true }, modifier = Modifier.focusIndicator(CircleShape), shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) { Text("Delete") }
        }
        StatusRow(status, isProblem, isWaiting)
    }

    // deleting needs a second OK, with Cancel focused, so a stray press can't lose the camera
    if (isDeleteAsked) {
        ConfirmDialog(
            "Delete ${cam.name}?", "Its live view, alerts and login are removed from this TV", "Delete",
            onConfirm = ::deleteCamera, onDismiss = { isDeleteAsked = false },
        )
    }
    // Back with changes not saved asks first
    BackHandler(enabled = isChanged && !isWaiting) { isDiscardAsked = true }
    if (isDiscardAsked) {
        ConfirmDialog(
            "Discard changes?", "Your changes haven't been saved", "Discard",
            onConfirm = { activity.finish() }, onDismiss = { isDiscardAsked = false },
        )
    }
}

/**
 * Add a camera in three steps, one main action each, so it's easy with a remote: find it on the network (or enter
 * its address), sign in to it (Connect asks where its video and alerts are and what it can detect), then see it live
 * and name it from a few common names. Back goes to the step before.
 */
@Composable
private fun AddCamera(activity: ComponentActivity, cams: MutableList<Cam>) {
    val ctx = activity
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(1) }
    val camera = remember { Cam() } // filled in as the steps go
    var model by remember { mutableStateOf("") } // as the camera announced itself on the network, or said on Connect
    val hostField = rememberTextFieldState()
    val userField = rememberTextFieldState()
    // most homes reuse a camera login: the saved ones are offered on Sign in, under the fields, as a shortcut
    val savedLogins = remember { Cam.listSavedLogins(cams) }
    val passField = rememberTextFieldState()
    var isPasswordShown by remember { mutableStateOf(false) }
    var isLoginRefused by remember { mutableStateOf(false) } // the last Connect: the password is what to fix
    val passwordFocus = remember { FocusRequester() }
    val otherNameField = rememberTextFieldState()
    var chosenName by remember { mutableStateOf<String?>(null) } // one of NAME_CHOICES, or OTHER_NAME
    val kindsSwitchedOff = remember { mutableStateListOf<String>() }
    val found = remember { mutableStateListOf<Triple<String, String, String>>() } // (address, model or "", ID) from the scan
    var isManual by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var isProblem by remember { mutableStateOf(false) }
    var isWaiting by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() } // each step's first control
    val manualFocus = remember { FocusRequester() }
    // Down from a step's last field goes to its main button, not whichever button is nearest below it
    val mainButtonFocus = remember { FocusRequester() }

    fun showStatus(text: String, problem: Boolean = false) {
        status = text
        isProblem = problem
    }

    fun goToStep(next: Int) {
        showStatus("")
        step = next
    }

    /**
     * Any camera that answers ONVIF discovery, and any with port 2020 open, where several brands answer (in case
     * discovery's broadcast doesn't get through), searched at the same time.
     */
    fun scanForCameras() {
        if (isWaiting) return
        showStatus("Looking for cameras…")
        found.clear()
        isWaiting = true
        scope.launch {
            val prefix = getSubnetPrefix(ctx)
            val hits = if (prefix == null) emptyList() else withContext(Dispatchers.IO) {
                val discovered = async { runCatching { Onvif.discoverCameras(3_000) }.getOrDefault(emptyList()) }
                val onPort2020 = probeSubnet(prefix).map { Triple(it, "", "") }
                // one entry per camera: discovery's, with its port and model, over the port search's
                (onPort2020 + discovered.await()).associateBy { it.first.substringBefore(':') }.values
                    .sortedBy { it.first.substringBefore(':').substringAfterLast('.').toIntOrNull() ?: 0 }
            }
            isWaiting = false
            when {
                prefix == null -> showStatus("This TV isn't connected to a network", problem = true)
                hits.isEmpty() -> showStatus("No cameras found. Make sure the camera is on this network or enter its address.",
                    problem = true)
                hits.size == 1 -> showStatus("Found 1 camera")
                else -> showStatus("Found ${hits.size} cameras")
            }
            found.addAll(hits)
        }
    }

    /** A camera picked from the scan or typed in: one that's already added opens its edit screen instead. */
    fun pickCamera(address: String, cameraModel: String, cameraId: String = "") {
        val addedIndex = cams.indexOfFirst { it.host == address }
        if (addedIndex >= 0) {
            ctx.startActivity(Intent(ctx, SetupActivity::class.java).putExtra("i", addedIndex))
            activity.finish()
            return
        }
        camera.host = address
        camera.deviceId = cameraId
        model = cameraModel
        goToStep(2)
    }

    fun submitAddress() {
        val address = hostField.text.toString().trim()
        if (address.isEmpty()) showStatus("Enter the camera's address", problem = true) else pickCamera(address, "")
    }

    /**
     * Signs in, then asks where its video and alerts are and what it can detect; on to step 3 when that works. With
     * savedLogin, signs in with another camera's login instead of the fields, which it leaves as they are.
     */
    fun connect(savedLogin: Pair<String, String>? = null) {
        if (isWaiting) return
        camera.user = savedLogin?.first ?: userField.text.toString().trim()
        camera.pass = savedLogin?.second ?: passField.text.toString()
        if (camera.user.isEmpty() || camera.pass.isEmpty()) {
            showStatus("Enter the username and password", problem = true)
            return
        }
        isWaiting = true
        isLoginRefused = false
        showStatus("Connecting to ${camera.host}…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val onvif = connectCamera(camera)
                    withContext(Dispatchers.Main) { showStatus("Getting the camera's video and alerts…") }
                    onvif.findServiceAddresses()
                    // what it can detect; one that won't say offers every kind we know
                    camera.kinds = runCatching { onvif.listEventKinds() }.getOrDefault(emptyList())
                    // its stable ID, to find it again if its address changes; typed-in cameras learn it here
                    if (camera.deviceId.isEmpty()) camera.deviceId = runCatching { Onvif.findDeviceId(camera.host) }.getOrDefault("")
                    model.ifEmpty { runCatching { onvif.getModel() }.getOrDefault("") }
                }
            }
            isWaiting = false
            result.onSuccess { cameraModel ->
                model = cameraModel
                goToStep(3)
            }.onFailure {
                isLoginRefused = Watcher.isAuthError(it) && savedLogin == null
                showStatus(describeConnectError(it, camera.host), problem = true)
            }
        }
    }

    fun addCamera() {
        camera.name = when (chosenName) {
            null -> model.ifEmpty { "Camera ${cams.size + 1}" }
            OTHER_NAME -> otherNameField.text.toString().trim().ifEmpty { model.ifEmpty { "Camera ${cams.size + 1}" } }
            else -> chosenName!!
        }
        camera.kindsOff = HashSet(kindsSwitchedOff)
        cams += camera
        Cam.saveCameras(ctx, cams)
        MotionService.syncService(ctx, true)
        // alerts are on by default, and a camera is what they need: ask for the pop-up permission now, not only when
        // alerts are switched off and on again (where it can't ask, the grid says what's missing)
        if (Cam.isAlertsOn(ctx) && !Settings.canDrawOverlays(ctx)) askOverlayPermission(ctx)
        activity.finish()
    }

    LaunchedEffect(Unit) { scanForCameras() }
    // each step's first control takes focus once it's drawn: after a scan, the first camera found, or typing an
    // address when there's none
    LaunchedEffect(step, isManual, isWaiting) {
        if (isWaiting) return@LaunchedEffect
        // after a Connect that failed, focus stays where it was (the chip or button just pressed), except a refused
        // typed password, which is what to fix
        if (step == 2 && status.isNotEmpty() && !isLoginRefused) return@LaunchedEffect
        val target = when {
            step == 1 && !isManual && found.isEmpty() -> manualFocus
            step == 2 && isLoginRefused -> passwordFocus
            else -> firstFocus
        }
        runCatching { target.requestFocus() }
    }
    BackHandler(enabled = step > 1 && !isWaiting) { goToStep(step - 1) }
    // each step opens at its top: the page scrolled for one step (the keyboard, a status line) mustn't push the
    // next step's title into the TV's edge
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }

    // a step fits on one TV screen: each has its one main action in view without scrolling
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).verticalScroll(scroll)
            .padding(horizontal = 96.dp, vertical = 32.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                StepIndicator(step, listOf("Find", "Sign in", "Name"))
                Text(
                    when (step) {
                        1 -> "Find your camera"
                        2 -> "Sign in to the camera"
                        else -> "Name your camera"
                    },
                    Modifier.padding(top = 4.dp), style = MaterialTheme.typography.headlineLargeEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (step > 1) {
                    Text(if (model.isEmpty()) camera.host else "$model at ${camera.host}", Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            // the camera live beside the title, so it's plain it works before it's added, and always in view
            if (step == 3) {
                ElevatedCard(Modifier.padding(start = 24.dp).size(288.dp, 162.dp), shape = MaterialTheme.shapes.large) {
                    LiveVideo(camera, hd = false, Modifier.fillMaxSize())
                }
            }
        }
        // the new step slides in from the right going forward, from the left going back. Only the new one moves: a
        // crossfade would keep the old step's fields, which share focus targets with the new ones, on screen meanwhile
        var previousStep by remember { mutableIntStateOf(step) }
        val stepEnter = remember(step) { Animatable(if (step == previousStep) 1f else 0f) }
        val isForward = step >= previousStep
        val stepSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
        LaunchedEffect(step) {
            stepEnter.animateTo(1f, stepSpec)
            previousStep = step
        }
        Column(Modifier.graphicsLayer {
            alpha = stepEnter.value.coerceIn(0f, 1f)
            translationX = (1f - stepEnter.value) * 64.dp.toPx() * (if (isForward) 1f else -1f)
        }) {
            when (step) {
                1 -> {
                    StatusRow(status, isProblem, isWaiting)
                    // the cameras found, then always a way to type an address
                    val rowCount = found.size + 1
                    SegmentedGroup(Modifier.padding(top = 8.dp)) {
                        found.forEachIndexed { i, (address, cameraModel, cameraId) ->
                            val shapes = ListItemDefaults.segmentedShapes(i, rowCount)
                            val isAdded = cams.any { it.host == address }
                            SegmentedListItem(
                                onClick = { pickCamera(address, cameraModel, cameraId) }, shapes = shapes,
                                modifier = (if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                    .focusIndicator(shapes.focusedShape, isInside = true),
                                supportingContent = { Text(if (isAdded) "Already added" else address) },
                            ) { Text(cameraModel.ifEmpty { address }) }
                        }
                        val manualShapes = ListItemDefaults.segmentedShapes(found.size, rowCount)
                        SegmentedListItem(
                            selected = isManual, onClick = { isManual = true }, shapes = manualShapes,
                            modifier = Modifier.focusRequester(manualFocus).focusIndicator(manualShapes.focusedShape, isInside = true),
                            supportingContent = { Text("When you know the camera's address") },
                        ) { Text("Enter address manually") }
                    }
                    if (isManual) {
                        Field(
                            "Camera address", ADDRESS_EXAMPLE, hostField,
                            Modifier.focusRequester(firstFocus).focusProperties { down = mainButtonFocus }, KeyboardType.Uri,
                            onDone = ::submitAddress,
                        )
                    }
                    FlowRow(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (isManual) {
                            Action("Next", isPrimary = true, Modifier.focusRequester(mainButtonFocus), ::submitAddress)
                        }
                        Action("Scan again") { scanForCameras() }
                    }
                }
                2 -> {
                    Hint(LOGIN_HINT)
                    Field("Username", "", userField, Modifier.focusRequester(firstFocus))
                    Field(
                        "Password", "", passField, Modifier.focusRequester(passwordFocus).focusProperties { down = mainButtonFocus },
                        keyboard = KeyboardType.Password, isSecret = true, isRevealed = isPasswordShown, onDone = { connect() },
                    )
                    ShowPasswordChip(isPasswordShown) { isPasswordShown = it }
                    FlowRow(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Action("Connect", isPrimary = true, Modifier.focusRequester(mainButtonFocus)) { connect() }
                    }
                    // a shortcut for a camera sharing a login already saved: one OK press, the password never shown
                    if (savedLogins.isNotEmpty()) {
                        Heading("Or use a saved login", topPadding = 24.dp)
                        FlowRow(
                            Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            savedLogins.forEach { (login, names) ->
                                FilterChip(
                                    selected = false, onClick = { connect(login) },
                                    label = { Text("${login.first} · ${listCameraNames(names)}") },
                                    modifier = Modifier.focusIndicator(FilterChipDefaults.shape),
                                )
                            }
                        }
                    }
                    StatusRow(status, isProblem, isWaiting)
                }
                else -> {
                    Heading("Name", topPadding = 16.dp)
                    FlowRow(
                        Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        (NAME_CHOICES + OTHER_NAME).forEachIndexed { i, name ->
                            FilterChip(
                                selected = chosenName == name,
                                onClick = { chosenName = if (chosenName == name) null else name },
                                label = { Text(name) },
                                modifier = (if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                    .focusIndicator(FilterChipDefaults.shape),
                            )
                        }
                    }
                    if (chosenName == OTHER_NAME) Field("Name", "Back yard", otherNameField)
                    Heading("Alert on the TV for", topPadding = 16.dp)
                    KindChips(camera.listKinds(), kindsSwitchedOff)
                    FlowRow(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Action("Add camera", isPrimary = true) { addCamera() }
                    }
                }
            }
        }
    }
}

/** Camera names for one line: "Front door", "Front door and Garage", "Front door, Garage and 2 more". */
internal fun listCameraNames(names: List<String>): String = when (names.size) {
    1 -> names[0]
    2 -> "${names[0]} and ${names[1]}"
    else -> "${names[0]}, ${names[1]} and ${names.size - 2} more"
}

// Names most cameras get: picked with the remote instead of typed
private val NAME_CHOICES = listOf("Front door", "Back door", "Driveway", "Garage", "Gate", "Garden", "Living room")
private const val OTHER_NAME = "Other"
private const val ADDRESS_EXAMPLE = "192.168.1.50 or 192.168.1.50:8080"
private const val LOGIN_HINT = "Use the camera's ONVIF login. Many cameras set this up separately in their own app."

/**
 * Where adding a camera is up to: a numbered circle per step joined by lines, a check once it's done, filled for the
 * current one and outlined for the ones to come. Screen readers hear "Step 2 of 3, Sign in".
 */
@Composable
private fun StepIndicator(step: Int, labels: List<String>) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.padding(bottom = 12.dp).semantics(mergeDescendants = true) {
            contentDescription = "Step $step of ${labels.size}, ${labels[step - 1]}"
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val number = i + 1
            val isReached = number <= step
            if (i > 0) {
                Box(Modifier.padding(horizontal = 12.dp).size(40.dp, 2.dp)
                    .background(if (isReached) colors.primary else colors.outlineVariant))
            }
            Box(
                Modifier.size(28.dp).then(
                    if (isReached) Modifier.background(colors.primary, CircleShape)
                    else Modifier.border(2.dp, colors.outline, CircleShape)
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (number < step) {
                    Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), colors.onPrimary)
                } else {
                    Text("$number", style = MaterialTheme.typography.labelLargeEmphasized,
                        color = if (isReached) colors.onPrimary else colors.onSurfaceVariant)
                }
            }
            Text(
                label, Modifier.padding(start = 8.dp),
                style = if (number == step) MaterialTheme.typography.titleSmallEmphasized else MaterialTheme.typography.titleSmall,
                color = if (number == step) colors.onSurface else colors.onSurfaceVariant,
            )
        }
    }
}

/** "Show password": typing blind on a TV keyboard is easy to get wrong, so it can be checked before Connect. */
@Composable
private fun ShowPasswordChip(isShown: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = isShown, onClick = { onChange(!isShown) }, label = { Text("Show password") },
        modifier = Modifier.padding(top = 8.dp).focusIndicator(FilterChipDefaults.shape),
        leadingIcon = if (isShown) ({ Icon(painterResource(R.drawable.ic_check), null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
    )
}

/** A camera's detections as chips, on unless switched off. */
@Composable
private fun KindChips(kinds: List<String>, kindsSwitchedOff: MutableList<String>) {
    FlowRow(
        Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (kind in kinds) {
            val isKindOn = kind !in kindsSwitchedOff
            FilterChip(
                selected = isKindOn,
                onClick = { if (isKindOn) kindsSwitchedOff.add(kind) else kindsSwitchedOff.remove(kind) },
                label = { Text(kind) },
                modifier = Modifier.focusIndicator(FilterChipDefaults.shape),
                leadingIcon = if (isKindOn) ({ Icon(painterResource(R.drawable.ic_check), null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
            )
        }
    }
}

/**
 * What the screen is doing or found, with the loader while it works, read out by screen readers as it changes. It
 * often sits past the bottom of the TV, so each change scrolls it into view.
 */
@Composable
private fun StatusRow(status: String, isProblem: Boolean, isWaiting: Boolean) {
    AnimatedVisibility(
        status.isNotEmpty(),
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
    ) {
        val statusView = remember { BringIntoViewRequester() }
        LaunchedEffect(status) { statusView.bringIntoView() }
        Row(
            Modifier.bringIntoViewRequester(statusView).padding(top = 12.dp).heightIn(min = 48.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(isWaiting, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                LoadingIndicator(Modifier.padding(end = 12.dp).size(48.dp))
            }
            Text(
                status, Modifier.widthIn(max = TEXT_WIDTH), style = MaterialTheme.typography.titleMedium,
                color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Why connecting failed, in plain words: a refused login, a camera that can't be reached, or what it said. */
private fun describeConnectError(error: Throwable, host: String): String = when {
    Watcher.isAuthError(error) -> "Wrong username or password. Use the camera's ONVIF login."
    error is ConnectException || error is SocketTimeoutException || error is UnknownHostException ->
        "Can't reach $host. Check the address and that the camera is on."
    else -> "Couldn't connect to the camera".also { Log.w("peekwell", "$host: couldn't connect (${error.message})") }
}

/**
 * A Material confirmation for a destructive action: Cancel is focused, so OK straight away is always safe, and the
 * action is in the error colour.
 */
@Composable
internal fun ConfirmDialog(title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm, modifier = Modifier.focusIndicator(CircleShape),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            FilledTonalButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelFocus).focusIndicator(CircleShape)) {
                Text("Cancel")
            }
        },
    )
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
}

/** Pop-ups over other apps need a permission that many TVs have no Settings screen for; why not, when it can't ask. */
private fun askOverlayPermission(ctx: Context): String? = try {
    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
    null
} catch (e: ActivityNotFoundException) {
    "This TV can't allow pop-ups from its settings. Allow them from a computer with adb shell appops set " +
            ctx.packageName + " SYSTEM_ALERT_WINDOW allow"
}

/** Settings for every camera: alerts on/off, label, sound, quiet hours. All read on every alert. */
@Composable
private fun AlertSettings(ctx: Context, askOverlayPermission: () -> Unit) {
    Heading("Alerts")
    var isAlertsOn by remember { mutableStateOf(Cam.isAlertsOn(ctx)) }
    var isLabelShown by remember { mutableStateOf(Cam.isLabelShown(ctx)) }
    SegmentedGroup {
        SwitchItem("Motion alerts", "Shows a live picture over any app when a camera detects something", isAlertsOn, 0, 2) { isOn ->
            isAlertsOn = isOn
            Cam.setAlerts(ctx, isOn)
            if (isOn && !Settings.canDrawOverlays(ctx)) askOverlayPermission()
            MotionService.syncService(ctx, false)
        }
        SwitchItem("Show what was detected", "A small tag at the bottom of the preview",
            isLabelShown, 1, 2) { isOn ->
            isLabelShown = isOn
            Cam.setShowLabel(ctx, isOn)
        }
    }

    Heading("Pause all alerts")
    val pauseMinutes = remember { mutableStateOf(Cam.getPauseMinutes(ctx).toString()) }
    var pausedUntil by remember { mutableLongStateOf(Cam.getPausedUntil(ctx)) }
    Connected(listOf("0" to "Off", "30" to "30 min", "60" to "1 hour", "180" to "3 hours", "480" to "8 hours"), pauseMinutes) {
        Cam.pauseAll(ctx, it.toInt())
        pausedUntil = Cam.getPausedUntil(ctx)
    }
    Hint(
        if (pausedUntil > 0) "Every camera is quiet until " + DateFormat.getTimeFormat(ctx).format(Date(pausedUntil))
        else "Every camera stays quiet for that long, then alerts come back on their own. To pause one camera until " +
            "you resume it, hold OK on it in the grid."
    )

    Heading("Pop-up size")
    val alertSize = remember { mutableStateOf(Cam.getAlertSize(ctx)) }
    Connected(listOf("small" to "Small", "medium" to "Medium", "large" to "Large"), alertSize) { Cam.setAlertSize(ctx, it) }

    Heading("Pop-up position")
    val alertPosition = remember { mutableStateOf(Cam.getAlertPosition(ctx)) }
    Connected(listOf("top_left" to "Top left", "top_right" to "Top right", "bottom_left" to "Bottom left",
        "bottom_right" to "Bottom right"), alertPosition) { Cam.setAlertPosition(ctx, it) }

    Heading("Alert sound")
    val alertSound = remember { mutableStateOf(Cam.getAlertSound(ctx)) }
    Connected(listOf(Cam.VOICE to "Voice", Cam.CHIME to "Chime", Cam.MUTE to "Mute"), alertSound) {
        Cam.setAlertSound(ctx, it)
    }
    Hint("Use Test alert on a camera to see and hear it")

    Heading("Quiet hours")
    var isQuietOn by remember { mutableStateOf(Cam.isQuietOn(ctx)) }
    var quietFrom by remember { mutableIntStateOf(Cam.getQuietFrom(ctx)) }
    var quietTo by remember { mutableIntStateOf(Cam.getQuietTo(ctx)) }
    val quietSound = remember { mutableStateOf(Cam.getQuietSound(ctx)) }
    fun saveQuietHours() = Cam.setQuiet(ctx, isQuietOn, quietFrom, quietTo, quietSound.value)
    SegmentedGroup {
        SwitchItem("Quiet hours", "Quieter alerts at night", isQuietOn, 0, 4) { isOn ->
            isQuietOn = isOn
            saveQuietHours()
        }
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(1, 4),
            trailingContent = { TimeStepper("From", quietFrom) { quietFrom = it; saveQuietHours() } },
        ) { Text("From") }
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(2, 4),
            trailingContent = { TimeStepper("To", quietTo) { quietTo = it; saveQuietHours() } },
        ) { Text("To") }
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(3, 4),
            supportingContent = { Text("Never louder than the alert sound") },
            trailingContent = { Connected(listOf(Cam.CHIME to "Chime", Cam.MUTE to "Mute"), quietSound) { saveQuietHours() } },
        ) { Text("Sound during quiet hours") }
    }
    Spacer(Modifier.height(24.dp))
}

/** Rows that belong together, as a Material 3 Expressive segmented list. */
@Composable
private fun SegmentedGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.padding(top = 8.dp).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) { content() }
}

/** An on/off row in a segmented group: OK on the row flips its switch. */
@Composable
private fun SwitchItem(title: String, description: String, isOn: Boolean, index: Int, count: Int, onChange: (Boolean) -> Unit) {
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    SegmentedListItem(
        checked = isOn, onCheckedChange = onChange, shapes = shapes,
        modifier = Modifier.focusIndicator(shapes.focusedShape, isInside = true),
        supportingContent = { Text(description) }, trailingContent = { Switch(isOn, onCheckedChange = null) },
    ) { Text(title) }
}

/** "−  23:00  +": 15-minute steps, wrapping round midnight. */
@Composable
private fun TimeStepper(label: String, minutes: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val earlier = { onChange((minutes - 15 + 24 * 60) % (24 * 60)) }
        val later = { onChange((minutes + 15) % (24 * 60)) }
        FilledTonalIconButton(
            onClick = earlier, shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.focusIndicator(CircleShape).repeatOnHold(earlier),
        ) { Icon(painterResource(R.drawable.ic_remove), "$label earlier") }
        Text(Cam.formatClock(minutes), Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onSurface)
        FilledTonalIconButton(
            onClick = later, shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.focusIndicator(CircleShape).repeatOnHold(later),
        ) { Icon(painterResource(R.drawable.ic_add), "$label later") }
    }
}

/**
 * Holding OK keeps stepping, about 5 steps a second and then twice as fast, so a time far away isn't dozens of
 * presses. The remote repeats a held key; a short press is still the button's own click.
 */
private fun Modifier.repeatOnHold(onStep: () -> Unit): Modifier = composed {
    val isHeld = remember { booleanArrayOf(false) }
    val currentStep by rememberUpdatedState(onStep)
    onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionCenter && event.key != Key.Enter) return@onPreviewKeyEvent false
        val repeats = event.nativeKeyEvent.repeatCount
        when {
            event.type == KeyEventType.KeyDown && repeats > 0 -> {
                isHeld[0] = true
                // the first repeat steps at once, so even a short hold moves exactly as a press would
                if (repeats == 1 || repeats % (if (repeats < 20) 4 else 2) == 0) currentStep()
                true
            }
            event.type == KeyEventType.KeyUp && isHeld[0] -> {
                isHeld[0] = false
                true // the hold did the stepping: no extra click on letting go
            }
            else -> false
        }
    }
}

/** A single choice as a connected Material 3 Expressive button group. */
@Composable
private fun Connected(options: List<Pair<String, String>>, selected: MutableState<String>, onPick: (String) -> Unit) {
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (value, text) ->
            val shapes: ToggleButtonShapes = when (i) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            val isChecked = selected.value == value
            ToggleButton(
                isChecked, { if (it) { selected.value = value; onPick(value) } },
                // the ring follows the button's own shape, which rounds fully once it's chosen; Left and Right stop at
                // the group's ends rather than jumping to whatever is nearest
                Modifier.focusIndicator(if (isChecked) shapes.checkedShape else shapes.shape, isInside = true)
                    .focusProperties {
                        if (i == 0) left = FocusRequester.Cancel
                        if (i == options.lastIndex) right = FocusRequester.Cancel
                    }
                    .semantics { role = Role.RadioButton },
                shapes = shapes,
                // a step lighter than the page's container tone, so an unchosen option still shows as a button on it
                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                Text(text)
            }
        }
    }
}

/**
 * A labelled text field. The keyboard opens on OK, not on focus: passing over a field with the remote mustn't open it.
 * Compose text fields open it only on focus or a touch, so OK switches "on focus" on, which starts the keyboard on
 * this focused field (only the state-based text fields honour showKeyboardOnFocus).
 */
@Composable
private fun Field(
    label: String, example: String, fieldState: TextFieldState, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, isSecret: Boolean = false, isRevealed: Boolean = false,
    onDone: (() -> Unit)? = null,
) {
    var isKeyboardWanted by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val fieldModifier = modifier.padding(top = 12.dp).focusIndicator(MaterialTheme.shapes.extraSmall).width(480.dp)
        .onFocusChanged { if (!it.isFocused) isKeyboardWanted = false }
        .onPreviewKeyEvent { event ->
            val isOk = event.key == Key.DirectionCenter || event.key == Key.Enter
            if (!isOk || isKeyboardWanted && event.type == KeyEventType.KeyUp) return@onPreviewKeyEvent false
            // the first press only: a held OK repeats, and the hold that opened this screen must not type here
            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                if (isKeyboardWanted) keyboardController?.show() else isKeyboardWanted = true
            }
            true
        }
    // The keyboard's action key: Next goes to the next field; the last field's Done does onDone (its step's main
    // action), so there's no hunting for the button with the D-pad after typing
    val keyboardOptions = KeyboardOptions(
        keyboardType = keyboard, imeAction = if (onDone == null) ImeAction.Next else ImeAction.Done,
        showKeyboardOnFocus = isKeyboardWanted,
    )
    val keyboardAction = KeyboardActionHandler { performDefault ->
        if (onDone == null) performDefault() else {
            keyboardController?.hide()
            onDone()
        }
    }
    if (isSecret) {
        OutlinedSecureTextField(
            fieldState, fieldModifier, label = { Text(label) }, keyboardOptions = keyboardOptions,
            onKeyboardAction = keyboardAction,
            textObfuscationMode = if (isRevealed) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        )
    } else {
        OutlinedTextField(
            fieldState, fieldModifier, label = { Text(label) },
            placeholder = if (example.isEmpty()) null else ({ Text(example) }),
            lineLimits = TextFieldLineLimits.SingleLine, keyboardOptions = keyboardOptions, onKeyboardAction = keyboardAction,
        )
    }
}

@Composable
private fun Action(text: String, isPrimary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val buttonModifier = modifier.focusIndicator(CircleShape)
    if (isPrimary) Button(onClick = onClick, modifier = buttonModifier, shapes = ButtonDefaults.shapes()) { Text(text) }
    else FilledTonalButton(onClick = onClick, modifier = buttonModifier, shapes = ButtonDefaults.shapes()) { Text(text) }
}

@Composable
private fun Heading(text: String, topPadding: Dp = 32.dp) = Text(text, Modifier.padding(top = topPadding),
    style = MaterialTheme.typography.titleLargeEmphasized, color = MaterialTheme.colorScheme.onSurface)

internal const val SOURCE_URL = "https://github.com/bytefoss/peekwell"

// About 75 characters of body text: longer lines are hard to read from across a room
private val TEXT_WIDTH = 560.dp

@Composable
private fun Hint(text: String) = Text(text, Modifier.padding(top = 8.dp).widthIn(max = TEXT_WIDTH),
    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

/**
 * The camera's ONVIF service, connected. An address without a port is tried on 2020, where several brands answer,
 * then on ONVIF's usual 80, and the camera keeps the one that answered.
 */
private fun connectCamera(camera: Cam): Onvif {
    val onvif = Onvif(camera)
    try {
        onvif.syncClock()
        return onvif
    } catch (e: IOException) {
        if (':' in camera.host) throw e
        val plainHost = camera.host
        camera.host = "$plainHost:80"
        try {
            return Onvif(camera).also { it.syncClock() }
        } catch (ignored: IOException) {
            camera.host = plainHost
            throw e // what went wrong on the first try
        }
    }
}

/** "192.168.1." for a TV at 192.168.1.20, from its active network's IPv4 address; null when offline. */
private fun getSubnetPrefix(ctx: Context): String? {
    val connectivity = ctx.getSystemService(ConnectivityManager::class.java)
    val linkProperties = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return null
    for (linkAddress in linkProperties.linkAddresses) {
        val ip = (linkAddress.address as? Inet4Address)?.hostAddress ?: continue
        return ip.substring(0, ip.lastIndexOf('.') + 1)
    }
    return null
}

/** Every address on the subnet with ONVIF's port open, in address order. */
// ponytail: assumes a /24 home network; a bigger subnet only gets its own /24 searched
private fun probeSubnet(prefix: String): List<String> {
    val pool = Executors.newFixedThreadPool(64)
    val tries = (1..254).map { i -> pool.submit<String?> { "$prefix$i".takeIf { isPortOpen(it, 2020) } } }
    val hits = tries.mapNotNull { runCatching { it.get() }.getOrNull() }
    pool.shutdown()
    return hits
}

private fun isPortOpen(ip: String, port: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(ip, port), 500) }
    true
} catch (e: IOException) {
    false
}
