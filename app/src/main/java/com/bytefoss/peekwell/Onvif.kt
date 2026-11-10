package com.bytefoss.peekwell

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Just enough ONVIF for a camera on the home network: check the login, find where its events and video are, and pull
 * its motion events. Its workarounds come from cameras met so far (a C320WS, for one); any ONVIF camera answers the
 * same requests.
 */
class Onvif @JvmOverloads constructor(
    private val cam: Cam,
    /**
     * The camera's device service: for a host without a port, :2020/onvif/service, where several brands answer;
     * with a port, ONVIF's standard path. Tests pass a camera at another address.
     */
    private val service: String = if (':' in cam.host) "http://${cam.host}/onvif/device_service"
    else "http://${cam.host}:2020/onvif/service",
) {
    // Where events are, as the camera said when it was saved; its device service when it wasn't asked
    private val events = cam.eventsUrl.ifEmpty { service }
    private var offsetMs = 0L // camera clock minus ours
    @Volatile private var pulling: HttpURLConnection? = null // the pull in flight, so abort() can cut it short

    /** The login digest carries a timestamp the camera checks against its own clock, so read that clock first. */
    @Throws(IOException::class)
    fun syncClock() {
        val response = postSoap(service, "<GetSystemDateAndTime xmlns=\"http://www.onvif.org/ver10/device/wsdl\"/>",
            null, auth = false, pull = false, timeoutMs = 10_000)
        val dateIndex = response.indexOf("UTCDateTime")
        if (dateIndex < 0) return
        val utcDateTime = response.substring(dateIndex)
        try {
            val calendar = Calendar.getInstance(UTC)
            calendar.clear()
            calendar.set(parseTagNumber(utcDateTime, "Year"), parseTagNumber(utcDateTime, "Month") - 1, parseTagNumber(utcDateTime, "Day"), parseTagNumber(utcDateTime, "Hour"), parseTagNumber(utcDateTime, "Minute"), parseTagNumber(utcDateTime, "Second"))
            offsetMs = calendar.timeInMillis - System.currentTimeMillis()
        } catch (ignored: NumberFormatException) {
            // keep our own clock
        }
    }

    @Throws(IOException::class)
    fun getModel(): String {
        val response = postSoap(service, "<GetDeviceInformation xmlns=\"http://www.onvif.org/ver10/device/wsdl\"/>",
            null, auth = true, pull = false, timeoutMs = 10_000)
        return getTagText(response, "Manufacturer") + " " + getTagText(response, "Model")
    }

    /**
     * What a problem report says about the camera: make, model, hardware and firmware (never its serial number),
     * then its ONVIF version and profiles when it says. Throws when it won't give its device information, usually a
     * refused login.
     */
    @Throws(IOException::class)
    fun describeDevice(): List<String> {
        val information = postSoap(service, "<GetDeviceInformation xmlns=\"$DEVICE\"/>",
            null, auth = true, pull = false, timeoutMs = 10_000)
        val lines = describeDeviceInformation(information).toMutableList()
        runCatching {
            postSoap(service, "<GetCapabilities xmlns=\"$DEVICE\"><Category>Device</Category></GetCapabilities>",
                null, auth = true, pull = false, timeoutMs = 10_000)
        }.getOrNull()?.let { findOnvifVersion(it) }?.let { lines += "ONVIF version: $it" }
        runCatching {
            postSoap(service, "<GetScopes xmlns=\"$DEVICE\"/>", null, auth = true, pull = false, timeoutMs = 10_000)
        }.getOrNull()?.let { listOnvifProfiles(it) }?.takeIf { it.isNotEmpty() }?.let { lines += "ONVIF profiles: " + it.joinToString(", ") }
        return lines
    }

    /**
     * Asks the camera where its events and video are, and stores them on the camera: the event address, and RTSP
     * addresses for its largest (HD) and smallest video profiles. Until a camera is asked, the app assumes the
     * common :2020/onvif/service, stream1 and stream2. Returns its video profiles as "H264 1920x1080", for a problem
     * report.
     */
    @Throws(IOException::class)
    fun findServiceAddresses(): List<String> {
        val capabilities = postSoap(service, "<GetCapabilities xmlns=\"$DEVICE\"><Category>All</Category></GetCapabilities>",
            null, auth = true, pull = false, timeoutMs = 10_000)
        val eventsUrl = getTagText(capabilities.substringAfter("Events>", ""), "XAddr")
        val mediaUrl = getTagText(capabilities.substringAfter("Media>", ""), "XAddr").ifEmpty { service }
        val profilesResponse = postSoap(mediaUrl, "<GetProfiles xmlns=\"$MEDIA\"/>",
            null, auth = true, pull = false, timeoutMs = 10_000)
        // (token, width) of every profile that has video
        val videoProfiles = PROFILE.findAll(profilesResponse).mapNotNull { profile ->
            val width = getTagText(profile.groupValues[2], "Width").toIntOrNull() ?: return@mapNotNull null
            profile.groupValues[1] to width
        }.toList()
        if (videoProfiles.isEmpty()) throw IOException("the camera has no video profiles")
        cam.streamUrlHd = getStreamUri(mediaUrl, videoProfiles.maxBy { it.second }.first)
        cam.streamUrlSd = getStreamUri(mediaUrl, videoProfiles.minBy { it.second }.first)
        cam.eventsUrl = eventsUrl
        return PROFILE.findAll(profilesResponse).mapNotNull { profile ->
            val encoder = profile.groupValues[2].substringAfter("VideoEncoderConfiguration", "")
            val width = getTagText(encoder, "Width").ifEmpty { return@mapNotNull null }
            "${getTagText(encoder, "Encoding")} ${width}x${getTagText(encoder, "Height")}".trim()
        }.toList()
    }

    @Throws(IOException::class)
    private fun getStreamUri(mediaUrl: String, profileToken: String): String {
        val response = postSoap(mediaUrl, "<GetStreamUri xmlns=\"$MEDIA\"><StreamSetup>" +
                "<Stream xmlns=\"$SCHEMA\">RTP-Unicast</Stream><Transport xmlns=\"$SCHEMA\"><Protocol>RTSP</Protocol>" +
                "</Transport></StreamSetup><ProfileToken>${escapeXml(profileToken)}</ProfileToken></GetStreamUri>",
            null, auth = true, pull = false, timeoutMs = 10_000)
        val streamUri = getTagText(response, "Uri")
        if (!streamUri.startsWith("rtsp://")) throw IOException("no RTSP address for profile $profileToken")
        return streamUri
    }

    /**
     * A pull point that lives lifeS seconds unless renewed. Keep it short: a C320WS serves only one pull point at a
     * time, so one left behind by a killed app blocks every new one until it expires.
     */
    @Throws(IOException::class)
    fun subscribe(lifeS: Int): String {
        val response = postSoap(events, "<CreatePullPointSubscription xmlns=\"$EVENTS\">" +
                "<InitialTerminationTime>PT${lifeS}S</InitialTerminationTime></CreatePullPointSubscription>",
            "$EVENTS/EventPortType/CreatePullPointSubscriptionRequest", auth = true, pull = false, timeoutMs = 10_000)
        val address = getTagText(response, "Address")
        if (address.isEmpty()) throw IOException("no subscription address")
        return address
    }

    /**
     * The detections the camera says it can send (ONVIF GetEventProperties), as labels most important first; see
     * listOfferedLabels.
     */
    @Throws(IOException::class)
    fun listEventKinds(): List<String> {
        val response = postSoap(cam.eventsUrl.ifEmpty { service }, "<GetEventProperties xmlns=\"$EVENTS\"/>",
            "$EVENTS/EventPortType/GetEventPropertiesRequest", auth = true, pull = false, timeoutMs = 10_000)
        return listOfferedLabels(response)
    }

    /** Pushes the pull point's end to lifeS seconds from now. */
    @Throws(IOException::class)
    fun renew(sub: String, lifeS: Int) {
        postSoap(sub, "<Renew xmlns=\"$WSN\"><TerminationTime>PT${lifeS}S</TerminationTime></Renew>",
            null, auth = true, pull = false, timeoutMs = 10_000)
    }

    /** Waits up to waitS seconds for events; the labels of what fired (see listDetectedLabels()), empty when nothing did. */
    @Throws(IOException::class)
    fun pullEvents(sub: String, waitS: Int): List<String> {
        val response = postSoap(sub, "<PullMessages xmlns=\"$EVENTS\">" +
                "<Timeout>PT${waitS}S</Timeout><MessageLimit>10</MessageLimit></PullMessages>",
            null, auth = true, pull = true, timeoutMs = (waitS + 10) * 1000)
        // what the camera sent, for anyone checking their camera with `adb logcat -s peekwell`; no login in it.
        // Rule names which detector fired (MyPeopleDetectorRule, ...): what tells apart kinds sharing one flag
        val seen = listSimpleItems(response).filter { it.first.startsWith("Is") || it.first == "Rule" }
            .joinToString("") { " ${it.first}=${it.second}" }
        Log.d("peekwell", "${cam.host}: pull ${response.length} bytes," + seen.ifEmpty { " no events" })
        return listDetectedLabels(response)
    }

    /** Releases the pull point at once; when the camera can't be reached, it expires on its own. */
    fun unsubscribe(sub: String) {
        try {
            postSoap(sub, "<Unsubscribe xmlns=\"$WSN\"/>", null, auth = true, pull = false, timeoutMs = 5_000)
        } catch (ignored: IOException) {
            // it expires on its own
        }
    }

    /** Cuts short a pull waiting on another thread (it throws); other requests, like Unsubscribe, are left alone. */
    fun abortPull() {
        pulling?.disconnect()
    }

    private fun postSoap(url: String, body: String, action: String?, auth: Boolean, pull: Boolean, timeoutMs: Int): String {
        val header = StringBuilder()
        if (auth) header.append(buildSecurityHeader())
        if (action != null) header.append("<a:Action>").append(action).append("</a:Action>")
        // Kept small: a C320WS answered this short form of Unsubscribe and Renew on its pull point and
        // released the subscription, where the longer standard form left it taken.
        val xml = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"" +
                " xmlns:a=\"http://www.w3.org/2005/08/addressing\">" +
                "<s:Header>$header</s:Header><s:Body>$body</s:Body></s:Envelope>"
        var digestLogin: String? = null // the HTTP Digest login, once the camera has asked for one
        while (true) {
            val connection = URL(url).openConnection() as HttpURLConnection
            if (pull) pulling = connection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = timeoutMs
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
                // some cameras (a C320WS) close the connection after each answer; a pooled one then fails with
                // "unexpected end of stream"
                connection.setRequestProperty("Connection", "close")
                if (digestLogin != null) connection.setRequestProperty("Authorization", digestLogin)
                connection.outputStream.use { it.write(xml.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                // Some cameras want an HTTP Digest login as well as, or instead of, the WS-Security one in the
                // message: answer the challenge once
                val challenge = connection.getHeaderField("WWW-Authenticate").orEmpty()
                if (code == 401 && auth && digestLogin == null && challenge.startsWith("Digest", ignoreCase = true)) {
                    digestLogin = buildDigestLogin(challenge, URL(url).file.ifEmpty { "/" })
                    continue
                }
                val response = readText(if (code < 400) connection.inputStream else connection.errorStream)
                if (code >= 400) {
                    val why = getTagText(response, "Text")
                    throw IOException(why.ifEmpty { "HTTP $code" })
                }
                return response
            } finally {
                if (pull) pulling = null
                connection.disconnect()
            }
        }
    }

    /** The HTTP Digest (RFC 7616, MD5) answer to the camera's challenge, with qop "auth" when it offers it. */
    private fun buildDigestLogin(challenge: String, uri: String): String {
        val fields = DIGEST_FIELD.findAll(challenge).associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
        val realm = fields["realm"].orEmpty()
        val nonce = fields["nonce"].orEmpty()
        val ha1 = getMd5Hex("${cam.user}:$realm:${cam.pass}")
        val ha2 = getMd5Hex("POST:$uri")
        val isQopAuth = fields["qop"].orEmpty().split(',').any { it.trim() == "auth" }
        val cnonce = ByteArray(8).also { RNG.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val response = if (isQopAuth) getMd5Hex("$ha1:$nonce:00000001:$cnonce:auth:$ha2") else getMd5Hex("$ha1:$nonce:$ha2")
        var login = "Digest username=\"${cam.user}\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", " +
                "response=\"$response\", algorithm=MD5"
        if (isQopAuth) login += ", qop=auth, nc=00000001, cnonce=\"$cnonce\""
        fields["opaque"]?.let { login += ", opaque=\"$it\"" }
        return login
    }

    /** WS-Security UsernameToken with a password digest: Base64(SHA-1(nonce + created + password)). */
    private fun buildSecurityHeader(): String {
        val nonce = ByteArray(16).also { RNG.nextBytes(it) }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = UTC }
        val created = dateFormat.format(Date(System.currentTimeMillis() + offsetMs))
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(nonce)
        sha.update(created.toByteArray(Charsets.UTF_8))
        sha.update(cam.pass.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return "<Security xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">" +
                "<UsernameToken><Username>" + escapeXml(cam.user) + "</Username>" +
                "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" +
                b64.encodeToString(sha.digest()) + "</Password>" +
                "<Nonce>" + b64.encodeToString(nonce) + "</Nonce>" +
                "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" +
                created + "</Created></UsernameToken></Security>"
    }

    companion object {
        // Detectors report Is* flags: IsMotion, IsPeople, IsLineCross, IsIntrusion, IsTamper, IsTPSmartEvent.
        // Attribute order varies: a C320WS (fw 1.6.6) sends Value before Name, so read them separately.
        private val ITEM = Regex("""<(?:[\w.-]+:)?SimpleItem\s([^>]*)>""")
        private val NAME = Regex("""\bName="(\w+)"""")
        private val VALUE = Regex("""\bValue="([^"]*)"""")
        private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
        private val RNG = SecureRandom()
        private val IPV4_URL = Regex("""http://\d+\.\d+\.\d+\.\d+(:\d+)?/.*""")
        private const val EVENTS = "http://www.onvif.org/ver10/events/wsdl"
        private const val WSN = "http://docs.oasis-open.org/wsn/b-2"
        private const val DEVICE = "http://www.onvif.org/ver10/device/wsdl"
        private const val MEDIA = "http://www.onvif.org/ver10/media/wsdl"
        private const val SCHEMA = "http://www.onvif.org/ver10/schema"
        private val DIGEST_FIELD = Regex("""(\w+)=(?:"([^"]*)"|([^\s,]*))""")
        private val DISCOVERY_PROBE = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" " +
                "xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" " +
                "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
                "<e:Header><w:MessageID>uuid:%s</w:MessageID>" +
                "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
                "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>" +
                "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>")
        // One media profile: its token, and what's inside it (its video encoder's Width, if it has video)
        private val PROFILE = Regex("""<(?:[\w.-]+:)?Profiles\b[^>]*\btoken="([^"]+)"[^>]*>(.*?)</(?:[\w.-]+:)?Profiles>""",
            RegexOption.DOT_MATCHES_ALL)

        // Detector flag -> pop-up label for the flags we know, most important first: when several fire together, the
        // first one wins. A C320WS sends vehicles as IsVehicle on its TPSmartEvent topic, though its event list
        // doesn't mention it.
        private val KINDS = listOf(
            "IsPeople" to "Person", "IsVehicle" to "Vehicle", "IsLineCross" to "Line crossing",
            "IsIntrusion" to "Intrusion", "IsTamper" to "Tamper", "IsTPSmartEvent" to "Smart event",
        )
        private val ITEM_DESCRIPTION = Regex("""<(?:[\w.-]+:)?SimpleItemDescription\s([^>]*)>""")
        private val TYPE = Regex("""\bType="([^"]*)"""")
        private val DATA = Regex("""<(?:[\w.-]+:)?Data>(.*?)</(?:[\w.-]+:)?Data>""", RegexOption.DOT_MATCHES_ALL)

        /** The labels we know, most important first, Motion last: a camera's choices when it was never asked. */
        @JvmStatic
        fun listLabels(): List<String> = KINDS.map { it.second } + "Motion"

        /**
         * How important a label is: 0 for Person, then down KINDS; then detections we don't know (a camera's own, as
         * getFlagLabel names them), and Motion last.
         */
        @JvmStatic
        fun rankLabel(kind: String): Int = when (kind) {
            "Motion" -> KINDS.size + 1
            else -> KINDS.indexOfFirst { it.second == kind }.let { if (it < 0) KINDS.size else it }
        }

        /** A flag's label: ours for the flags we know, Motion for IsMotion, else from its name ("IsFaceFound": "Face found"). */
        @JvmStatic
        fun getFlagLabel(flag: String): String = KINDS.firstOrNull { it.first == flag }?.second
            ?: if (flag == "IsMotion") "Motion"
            else flag.removePrefix("Is").replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase().replaceFirstChar { it.uppercase() }

        /**
         * What a camera's GetEventProperties answer offers: the label of every yes/no Is* flag its topics send,
         * most important first. A camera that offers a TP smart event also offers Vehicle, which a C320WS sends there
         * without listing it.
         */
        @JvmStatic
        fun listOfferedLabels(xml: String): List<String> {
            val flags = DATA.findAll(xml).flatMap { ITEM_DESCRIPTION.findAll(it.groupValues[1]) }.mapNotNull { item ->
                val attrs = item.groupValues[1]
                val name = NAME.find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
                name.takeIf { it.startsWith("Is") && TYPE.find(attrs)?.groupValues?.get(1).orEmpty().endsWith("boolean") }
            }.toSet()
            val labels = flags.map(::getFlagLabel).toMutableSet()
            if ("IsTPSmartEvent" in flags) labels += "Vehicle"
            return labels.sortedBy(::rankLabel)
        }

        /** The label of the most important detector that reported true (see listDetectedLabels()); null when none did. */
        @JvmStatic
        fun detectLabel(xml: String): String? = listDetectedLabels(xml).firstOrNull()

        /**
         * Every label that reported true, most important first (see rankLabel), a flag we don't know under its own
         * name (getFlagLabel). Empty when nothing fired. All of them, so a kind switched off in the settings can give
         * way to the next.
         */
        @JvmStatic
        fun listDetectedLabels(xml: String): List<String> =
            listSimpleItems(xml).filter { it.first.startsWith("Is") && it.second == "true" }
                .map { getFlagLabel(it.first) }.distinct().sortedBy(::rankLabel)

        /** Every SimpleItem as (name, value), whatever the attribute order. */
        private fun listSimpleItems(xml: String): List<Pair<String, String>> = ITEM.findAll(xml).mapNotNull { item ->
            val attrs = item.groupValues[1]
            val name = NAME.find(attrs) ?: return@mapNotNull null
            val value = VALUE.find(attrs) ?: return@mapNotNull null
            name.groupValues[1] to value.groupValues[1]
        }.toList()

        /** Text of the first element with this local name, whatever its namespace prefix; "" when absent. */
        @JvmStatic
        fun getTagText(xml: String, name: String): String =
            Regex("""<(?:[\w.-]+:)?$name(?:\s[^>]*)?>([^<]*)<""").find(xml)?.groupValues?.get(1)?.trim() ?: ""

        /** From a GetDeviceInformation answer: "Device: Make Model", "Hardware: …", "Firmware: …", leaving out blanks. */
        @JvmStatic
        fun describeDeviceInformation(xml: String): List<String> = listOfNotNull(
            "Device: " + (getTagText(xml, "Manufacturer") + " " + getTagText(xml, "Model")).trim(),
            getTagText(xml, "HardwareId").ifEmpty { null }?.let { "Hardware: $it" },
            getTagText(xml, "FirmwareVersion").ifEmpty { null }?.let { "Firmware: $it" },
        )

        /** The newest ONVIF version a GetCapabilities answer lists under SupportedVersions ("17.06"); null when none. */
        @JvmStatic
        fun findOnvifVersion(xml: String): String? =
            Regex("""SupportedVersions>\s*<(?:[\w.-]+:)?Major>(\d+)<.*?<(?:[\w.-]+:)?Minor>(\d+)<""", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
                .maxWithOrNull(compareBy({ it.first }, { it.second }))
                // since 2016 versions are year.month (17.06); before that 2.4
                ?.let { (major, minor) -> "$major." + if (major >= 16) minor.toString().padStart(2, '0') else "$minor" }

        /** The ONVIF profiles a GetScopes answer claims, as their letters: Streaming is S, the newer ones are named by letter. */
        @JvmStatic
        fun listOnvifProfiles(xml: String): List<String> =
            Regex("""onvif://www\.onvif\.org/Profile/(\w+)""").findAll(xml)
                .map { if (it.groupValues[1].equals("Streaming", ignoreCase = true)) "S" else it.groupValues[1].uppercase() }
                .distinct().sorted().toList()

        /**
         * ONVIF cameras on the network that answer a WS-Discovery probe within waitMs, any brand: (address as the
         * camera form takes it, model, stable ID), in the order they answered. Sent twice: it's UDP, and one can get
         * lost.
         */
        @JvmStatic
        fun discoverCameras(waitMs: Int): List<Triple<String, String, String>> {
            val probe = DISCOVERY_PROBE.format(UUID.randomUUID()).toByteArray(Charsets.UTF_8)
            val found = LinkedHashMap<String, Triple<String, String, String>>()
            DatagramSocket().use { socket ->
                socket.soTimeout = 250
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(probe, probe.size, group, 3702)) }
                val buffer = ByteArray(65_535)
                val end = System.nanoTime() / 1_000_000 + waitMs
                while (System.nanoTime() / 1_000_000 < end) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val reply = String(buffer, 0, packet.length, Charsets.UTF_8)
                    val camera = readDiscoveryReply(reply) ?: continue
                    found.putIfAbsent(camera.first, Triple(camera.first, camera.second, readDiscoveryId(reply)))
                }
            }
            return found.values.toList()
        }

        /**
         * A camera's stable ID from its WS-Discovery answer (its endpoint reference, "urn:uuid:..."), the same however
         * its address changes; "" when it gives none.
         */
        @JvmStatic
        fun readDiscoveryId(xml: String): String = getTagText(xml.substringAfter("EndpointReference", ""), "Address")

        /** The stable ID of the camera discovery finds at host's address; "" when none answers there. */
        @JvmStatic
        fun findDeviceId(host: String): String = discoverCameras(3_000)
            .firstOrNull { it.first.substringBefore(':') == host.substringBefore(':') }?.third.orEmpty()

        /**
         * From a WS-Discovery answer: the camera's address as the camera form takes it (just the IP for port 2020,
         * ip:port otherwise) and its model from the scopes ("" when it gives none); null without an
         * IPv4 http address.
         */
        @JvmStatic
        fun readDiscoveryReply(xml: String): Pair<String, String>? {
            val serviceUrl = getTagText(xml, "XAddrs").split(' ').firstOrNull { IPV4_URL.matches(it) } ?: return null
            val uri = URI(serviceUrl)
            val port = if (uri.port == -1) 80 else uri.port
            val address = if (port == 2020) uri.host else "${uri.host}:$port"
            val scopes = getTagText(xml, "Scopes").split(' ')
            val model = (scopes.firstOrNull { "/hardware/" in it } ?: scopes.firstOrNull { "/name/" in it })
                ?.substringAfterLast('/')?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
            return address to model
        }

        private fun getMd5Hex(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private fun parseTagNumber(xml: String, name: String): Int = getTagText(xml, name).toInt()

        private fun escapeXml(text: String): String =
            text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        private fun readText(input: InputStream?): String = input?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
    }
}
