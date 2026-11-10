package com.bytefoss.peekwell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class OnvifTest {
    private static String pull(String item) {
        return "<tev:PullMessagesResponse><wsnt:NotificationMessage><wsnt:Topic>tns1:RuleEngine/CellMotionDetector/Motion"
                + "</wsnt:Topic><wsnt:Message><tt:Message UtcTime=\"2026-10-23T09:00:00Z\" PropertyOperation=\"Changed\">"
                + "<tt:Data>" + item + "</tt:Data></tt:Message></wsnt:Message></wsnt:NotificationMessage>"
                + "</tev:PullMessagesResponse>";
    }

    // Verbatim from a Tapo C320WS hw 2.0, fw 1.6.6 Build 260917 (envelope trimmed): Value comes before Name,
    // and every event is marked "Initialized", even mid-stream.
    private static final String C320WS_PERSON = "<tev:PullMessagesResponse><tev:CurrentTime>2026-11-05T20:56:35Z</tev:CurrentTime>"
            + "<tev:TerminationTime>2026-11-05T21:06:35Z</tev:TerminationTime><wsnt:NotificationMessage>"
            + "<wsnt:SubscriptionReference><wsa5:Address></wsa5:Address></wsnt:SubscriptionReference>"
            + "<wsnt:Topic Dialect=\"http://www.onvif.org/ver10/tev/topicExpression/ConcreteSet\">tns1:RuleEngine/PeopleDetector/People</wsnt:Topic>"
            + "<wsnt:Message><tt:Message PropertyOperation=\"Initialized\" UtcTime=\"2026-11-05T20:56:42Z\"><tt:Source>"
            + "<tt:SimpleItem Value=\"vsconf\" Name=\"VideoSourceConfigurationToken\"></tt:SimpleItem>"
            + "<tt:SimpleItem Value=\"VideoAnalyticsToken\" Name=\"VideoAnalyticsConfigurationToken\"></tt:SimpleItem>"
            + "<tt:SimpleItem Value=\"MyPeopleDetectorRule\" Name=\"Rule\"></tt:SimpleItem></tt:Source>"
            + "<tt:Data><tt:SimpleItem Value=\"true\" Name=\"IsPeople\"></tt:SimpleItem></tt:Data></tt:Message></wsnt:Message>"
            + "</wsnt:NotificationMessage></tev:PullMessagesResponse>";

    private static String on(String flag) {
        return pull("<tt:SimpleItem Name=\"" + flag + "\" Value=\"true\"/>");
    }

    @Test
    public void detectsByKind() {
        assertEquals("Person", Onvif.detectLabel(on("IsPeople")));
        assertEquals("Line crossing", Onvif.detectLabel(on("IsLineCross")));
        assertEquals("Intrusion", Onvif.detectLabel(on("IsIntrusion")));
        assertEquals("Tamper", Onvif.detectLabel(on("IsTamper")));
        assertEquals("Smart event", Onvif.detectLabel(on("IsTPSmartEvent")));
        assertEquals("Motion", Onvif.detectLabel(on("IsMotion")));
        assertEquals("Something new", Onvif.detectLabel(on("IsSomethingNew"))); // a detector we don't know yet still alerts
        assertNull(Onvif.detectLabel(pull("<tt:SimpleItem Name=\"IsMotion\" Value=\"false\"/>")));
        assertNull(Onvif.detectLabel("<tev:PullMessagesResponse/>"));
    }

    @Test
    public void mostSpecificWins() {
        assertEquals("Line crossing", Onvif.detectLabel(on("IsMotion") + on("IsLineCross")));
        assertEquals("Intrusion", Onvif.detectLabel(on("IsTPSmartEvent") + on("IsIntrusion") + on("IsMotion")));
    }

    @Test
    public void realC320wsReplyWithValueBeforeName() {
        assertEquals("Person", Onvif.detectLabel(C320WS_PERSON));
        assertNull(Onvif.detectLabel(C320WS_PERSON.replace("Value=\"true\" Name=\"IsPeople\"", "Value=\"false\" Name=\"IsPeople\"")));
    }

    // Same shape as the C320WS's person message; a vehicle arrived logged as
    // "pull 2697 bytes, Rule=MyTPSmartEventDetectorRule IsVehicle=true" (2026-11-06), a flag its event list omits.
    private static final String C320WS_VEHICLE = C320WS_PERSON
            .replace("PeopleDetector/People", "TPSmartEventDetector/TPSmartEvent")
            .replace("MyPeopleDetectorRule", "MyTPSmartEventDetectorRule")
            .replace("Name=\"IsPeople\"", "Name=\"IsVehicle\"");

    @Test
    public void vehicleFromTheSmartEventTopic() {
        assertEquals("Vehicle", Onvif.detectLabel(C320WS_VEHICLE));
        assertEquals("Person", Onvif.detectLabel(C320WS_VEHICLE + C320WS_PERSON));
        assertEquals("Vehicle", Onvif.detectLabel(C320WS_VEHICLE + on("IsLineCross") + on("IsMotion")));
        assertEquals("Vehicle detected", Announcer.buildPhrase("Vehicle"));
    }

    @Test
    public void visitKeepsTheMostImportantLabel() {
        assertEquals("Motion", MotionService.chooseVisitLabel(null, "Motion"));          // new visit: announced
        assertEquals("Vehicle", MotionService.chooseVisitLabel("Motion", "Vehicle"));    // upgrade: announced again
        assertEquals("Person", MotionService.chooseVisitLabel("Line crossing", "Person"));
        assertEquals("Person", MotionService.chooseVisitLabel("Person", "Vehicle"));     // person past a car: stays, quiet
        assertEquals("Vehicle", MotionService.chooseVisitLabel("Vehicle", "Line crossing"));
        assertEquals("Line crossing", MotionService.chooseVisitLabel("Line crossing", "Line crossing"));
    }

    @Test
    public void spokenPhrases() {
        assertEquals("Person detected", Announcer.buildPhrase("Person"));
        assertEquals("Motion detected", Announcer.buildPhrase("Motion"));
        assertEquals("Line crossing detected", Announcer.buildPhrase("Line crossing"));
        assertEquals("Intrusion detected", Announcer.buildPhrase("Intrusion"));
        assertEquals("Camera tampering detected", Announcer.buildPhrase("Tamper"));
        assertEquals("Smart event detected", Announcer.buildPhrase("Smart event"));
    }

    @Test
    public void personWinsOverEverything() {
        assertEquals("Person", Onvif.detectLabel(on("IsMotion") + on("IsLineCross")
                + pull("<tt:SimpleItem Value=\"true\" Name=\"IsPeople\"/>")));
    }

    @Test
    public void tagIgnoresPrefixAndAttributes() {
        String r = "<wsnt:SubscriptionReference><wsa5:Address>http://192.168.1.50:2020/event-0_2020</wsa5:Address>"
                + "</wsnt:SubscriptionReference><SOAP-ENV:Text xml:lang=\"en\">Authority failure</SOAP-ENV:Text>";
        assertEquals("http://192.168.1.50:2020/event-0_2020", Onvif.getTagText(r, "Address"));
        assertEquals("Authority failure", Onvif.getTagText(r, "Text"));
        assertEquals("", Onvif.getTagText(r, "Model"));
    }

    // Shaped like the C320WS's answer to a WS-Discovery probe (2026-11-08), and a camera on port 80
    private static String probeMatch(String xaddrs, String scopes) {
        return "<SOAP-ENV:Envelope><SOAP-ENV:Body><wsdd:ProbeMatches><wsdd:ProbeMatch><wsdd:Types>dn:NetworkVideoTransmitter"
                + "</wsdd:Types><wsdd:Scopes>" + scopes + "</wsdd:Scopes><wsdd:XAddrs>" + xaddrs + "</wsdd:XAddrs>"
                + "</wsdd:ProbeMatch></wsdd:ProbeMatches></SOAP-ENV:Body></SOAP-ENV:Envelope>";
    }

    @Test
    public void readsWhereADiscoveredCameraIs() {
        assertEquals(new kotlin.Pair<>("192.168.1.52", "C320WS"), Onvif.readDiscoveryReply(probeMatch(
                "http://192.168.1.52:2020/onvif/device_service",
                "onvif://www.onvif.org/name/C320WS onvif://www.onvif.org/hardware/C320WS onvif://www.onvif.org/Profile/Streaming")));
        // another brand on the default port, an IPv6 address listed first, a model with a space in it
        assertEquals(new kotlin.Pair<>("192.168.1.9:80", "DS 2CD2143"), Onvif.readDiscoveryReply(probeMatch(
                "http://[fe80::1]/onvif/device_service http://192.168.1.9/onvif/device_service",
                "onvif://www.onvif.org/hardware/DS%202CD2143 onvif://www.onvif.org/name/HIKVISION")));
        assertEquals(new kotlin.Pair<>("10.0.0.7:8000", ""), Onvif.readDiscoveryReply(probeMatch(
                "http://10.0.0.7:8000/onvif/device_service", "")));
        assertNull(Onvif.readDiscoveryReply(probeMatch("http://[fe80::1]/onvif/device_service", "")));
    }

    // Verbatim TopicSet from the C320WS's GetEventProperties answer (fw 1.6.6, 2026-11-06), whitespace removed
    private static final String C320WS_EVENT_LIST = ""
            + "<wstop:TopicSet><tns1:RuleEngine><CellMotionDetector><Motion wstop:topic=\"true\"><tt:MessageDescr"
            + "iption IsProperty=\"true\"><tt:Source><tt:SimpleItemDescription Type=\"tt:ReferenceToken\" Name=\"Vid"
            + "eoSourceConfigurationToken\"></tt:SimpleItemDescription><tt:SimpleItemDescription Type=\"tt:Refere"
            + "nceToken\" Name=\"VideoAnalyticsConfigurationToken\"></tt:SimpleItemDescription><tt:SimpleItemDescr"
            + "iption Type=\"xsd:string\" Name=\"Rule\"></tt:SimpleItemDescription></tt:Source><tt:Data><tt:SimpleI"
            + "temDescription Type=\"xsd:boolean\" Name=\"IsMotion\"></tt:SimpleItemDescription></tt:Data></tt:Mess"
            + "ageDescription></Motion></CellMotionDetector><TamperDetector><Tamper wstop:topic=\"true\"><tt:Mess"
            + "ageDescription IsProperty=\"true\"><tt:Source><tt:SimpleItemDescription Type=\"tt:ReferenceToken\" N"
            + "ame=\"VideoSourceConfigurationToken\"></tt:SimpleItemDescription><tt:SimpleItemDescription Type=\"t"
            + "t:ReferenceToken\" Name=\"VideoAnalyticsConfigurationToken\"></tt:SimpleItemDescription><tt:SimpleI"
            + "temDescription Type=\"xsd:string\" Name=\"Rule\"></tt:SimpleItemDescription></tt:Source><tt:Data><tt"
            + ":SimpleItemDescription Type=\"xsd:boolean\" Name=\"IsTamper\"></tt:SimpleItemDescription></tt:Data><"
            + "/tt:MessageDescription></Tamper></TamperDetector><IntrusionDetector><Intrusion wstop:topic=\"true"
            + "\"><tt:MessageDescription IsProperty=\"true\"><tt:Source><tt:SimpleItemDescription Type=\"tt:Referen"
            + "ceToken\" Name=\"VideoSourceConfigurationToken\"></tt:SimpleItemDescription><tt:SimpleItemDescripti"
            + "on Type=\"tt:ReferenceToken\" Name=\"VideoAnalyticsConfigurationToken\"></tt:SimpleItemDescription><"
            + "tt:SimpleItemDescription Type=\"xsd:string\" Name=\"Rule\"></tt:SimpleItemDescription></tt:Source><t"
            + "t:Data><tt:SimpleItemDescription Type=\"xsd:boolean\" Name=\"IsIntrusion\"></tt:SimpleItemDescriptio"
            + "n></tt:Data></tt:MessageDescription></Intrusion></IntrusionDetector><LineCrossDetector><LineCros"
            + "s wstop:topic=\"true\"><tt:MessageDescription IsProperty=\"true\"><tt:Source><tt:SimpleItemDescripti"
            + "on Type=\"tt:ReferenceToken\" Name=\"VideoSourceConfigurationToken\"></tt:SimpleItemDescription><tt:"
            + "SimpleItemDescription Type=\"tt:ReferenceToken\" Name=\"VideoAnalyticsConfigurationToken\"></tt:Simp"
            + "leItemDescription><tt:SimpleItemDescription Type=\"xsd:string\" Name=\"Rule\"></tt:SimpleItemDescrip"
            + "tion></tt:Source><tt:Data><tt:SimpleItemDescription Type=\"xsd:boolean\" Name=\"IsLineCross\"></tt:S"
            + "impleItemDescription></tt:Data></tt:MessageDescription></LineCross></LineCrossDetector><PeopleDe"
            + "tector><People wstop:topic=\"true\"><tt:MessageDescription IsProperty=\"true\"><tt:Source><tt:Simple"
            + "ItemDescription Type=\"tt:ReferenceToken\" Name=\"VideoSourceConfigurationToken\"></tt:SimpleItemDes"
            + "cription><tt:SimpleItemDescription Type=\"tt:ReferenceToken\" Name=\"VideoAnalyticsConfigurationTok"
            + "en\"></tt:SimpleItemDescription><tt:SimpleItemDescription Type=\"xsd:string\" Name=\"Rule\"></tt:Simp"
            + "leItemDescription></tt:Source><tt:Data><tt:SimpleItemDescription Type=\"xsd:boolean\" Name=\"IsPeop"
            + "le\"></tt:SimpleItemDescription></tt:Data></tt:MessageDescription></People></PeopleDetector><TPSm"
            + "artEventDetector><TPSmartEvent wstop:topic=\"true\"><tt:MessageDescription IsProperty=\"true\"><tt:S"
            + "ource><tt:SimpleItemDescription Type=\"tt:ReferenceToken\" Name=\"VideoSourceConfigurationToken\"></"
            + "tt:SimpleItemDescription><tt:SimpleItemDescription Type=\"tt:ReferenceToken\" Name=\"VideoAnalytics"
            + "ConfigurationToken\"></tt:SimpleItemDescription><tt:SimpleItemDescription Type=\"xsd:string\" Name="
            + "\"Rule\"></tt:SimpleItemDescription></tt:Source><tt:Data><tt:SimpleItemDescription Type=\"xsd:boole"
            + "an\" Name=\"IsTPSmartEvent\"></tt:SimpleItemDescription></tt:Data></tt:MessageDescription></TPSmart"
            + "Event></TPSmartEventDetector></tns1:RuleEngine></wstop:TopicSet>";

    @Test
    public void readsWhatACameraCanDetect() {
        // the C320WS lists six detectors; Vehicle comes with its smart event, which it doesn't list
        assertEquals(java.util.Arrays.asList("Person", "Vehicle", "Line crossing", "Intrusion", "Tamper", "Smart event", "Motion"),
                Onvif.listOfferedLabels(C320WS_EVENT_LIST));
        // another brand: only boolean Is* data flags count, not its source tokens or other data
        String other = "<tns1:RuleEngine><Face wstop:topic=\"true\"><tt:MessageDescription><tt:Source>"
                + "<tt:SimpleItemDescription Name=\"Rule\" Type=\"xsd:string\"/></tt:Source><tt:Data>"
                + "<tt:SimpleItemDescription Name=\"IsFace\" Type=\"xsd:boolean\"/>"
                + "<tt:SimpleItemDescription Name=\"Count\" Type=\"xsd:int\"/></tt:Data></tt:MessageDescription></Face>"
                + "<Motion wstop:topic=\"true\"><tt:MessageDescription><tt:Data>"
                + "<tt:SimpleItemDescription Name=\"IsMotion\" Type=\"xs:boolean\"/></tt:Data></tt:MessageDescription></Motion>"
                + "<People wstop:topic=\"true\"><tt:MessageDescription><tt:Data>"
                + "<tt:SimpleItemDescription Type=\"xsd:boolean\" Name=\"IsPeople\"/></tt:Data></tt:MessageDescription></People>"
                + "</tns1:RuleEngine>";
        assertEquals(java.util.Arrays.asList("Person", "Face", "Motion"), Onvif.listOfferedLabels(other));
    }

    @Test
    public void readsADiscoveredCamerasStableId() {
        // shaped like the C320WS's answer, its ID replaced
        String reply = "<SOAP-ENV:Envelope><SOAP-ENV:Header><wsa:RelatesTo>uuid:probe</wsa:RelatesTo></SOAP-ENV:Header>"
                + "<SOAP-ENV:Body><wsdd:ProbeMatches><wsdd:ProbeMatch><wsa:EndpointReference>"
                + "<wsa:Address>urn:uuid:12345678-0000-0000-0000-000000000000</wsa:Address></wsa:EndpointReference>"
                + "<wsdd:XAddrs>http://192.168.1.52:2020/onvif/device_service</wsdd:XAddrs>"
                + "</wsdd:ProbeMatch></wsdd:ProbeMatches></SOAP-ENV:Body></SOAP-ENV:Envelope>";
        assertEquals("urn:uuid:12345678-0000-0000-0000-000000000000", Onvif.readDiscoveryId(reply));
        assertEquals("", Onvif.readDiscoveryId("<wsdd:ProbeMatch></wsdd:ProbeMatch>"));
    }

    // Shaped like a C320WS's answers (whitespace removed); a serial number must never reach a report
    @Test
    public void deviceDescriptionForAReport() {
        String information = "<tds:GetDeviceInformationResponse><tds:Manufacturer>tp-link</tds:Manufacturer>"
                + "<tds:Model>C320WS</tds:Model><tds:FirmwareVersion>1.6.6 Build 260917 Rel.62025n</tds:FirmwareVersion>"
                + "<tds:SerialNumber>8C902D1A2B3C</tds:SerialNumber><tds:HardwareId>2.0</tds:HardwareId>"
                + "</tds:GetDeviceInformationResponse>";
        assertEquals(java.util.Arrays.asList("Device: tp-link C320WS", "Hardware: 2.0", "Firmware: 1.6.6 Build 260917 Rel.62025n"),
                Onvif.describeDeviceInformation(information));
        assertFalse(String.join(" ", Onvif.describeDeviceInformation(information)).contains("8C902D1A2B3C"));
        // blanks left out
        assertEquals(java.util.Collections.singletonList("Device: Acme X1"),
                Onvif.describeDeviceInformation("<Manufacturer>Acme</Manufacturer><Model>X1</Model>"));

        String capabilities = "<tt:Device><tt:System><tt:SupportedVersions><tt:Major>2</tt:Major><tt:Minor>40</tt:Minor>"
                + "</tt:SupportedVersions><tt:SupportedVersions><tt:Major>17</tt:Major><tt:Minor>6</tt:Minor>"
                + "</tt:SupportedVersions></tt:System></tt:Device>";
        assertEquals("17.06", Onvif.findOnvifVersion(capabilities));
        assertEquals("2.4", Onvif.findOnvifVersion("<SupportedVersions><Major>2</Major><Minor>4</Minor></SupportedVersions>"));
        assertNull(Onvif.findOnvifVersion("<tt:Device/>"));

        String scopes = "<tds:Scopes><tt:ScopeItem>onvif://www.onvif.org/Profile/Streaming</tt:ScopeItem></tds:Scopes>"
                + "<tds:Scopes><tt:ScopeItem>onvif://www.onvif.org/Profile/T</tt:ScopeItem></tds:Scopes>"
                + "<tds:Scopes><tt:ScopeItem>onvif://www.onvif.org/hardware/C320WS</tt:ScopeItem></tds:Scopes>";
        assertEquals(java.util.Arrays.asList("S", "T"), Onvif.listOnvifProfiles(scopes));
    }
}
