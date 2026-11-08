package com.bytefoss.peekwell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Which detections alert on the TV, and quiet hours. */
public class SettingsTest {
    private static Set<String> off(String... kinds) {
        return new HashSet<>(Arrays.asList(kinds));
    }

    private static String sample(String... flags) {
        StringBuilder x = new StringBuilder();
        for (String f : flags) x.append("<tt:SimpleItem Value=\"true\" Name=\"").append(f).append("\"/>");
        return x.toString();
    }

    @Test
    public void kindsInOrderOfImportance() {
        assertEquals(Arrays.asList("Person", "Line crossing", "Motion"),
                Onvif.listDetectedLabels(sample("IsMotion", "IsLineCross", "IsPeople")));
        // a detection we don't know goes under its own name, ahead of plain motion
        assertEquals(Arrays.asList("Something new", "Motion"), Onvif.listDetectedLabels(sample("IsMotion", "IsSomethingNew")));
        assertTrue(Onvif.listDetectedLabels("<tev:PullMessagesResponse/>").isEmpty());
    }

    @Test
    public void labelsMatchTheRanking() {
        List<String> labels = Onvif.listLabels();
        assertEquals(Arrays.asList("Person", "Vehicle", "Line crossing", "Intrusion", "Tamper", "Smart event", "Motion"),
                labels);
        for (int i = 1; i < labels.size(); i++) assertTrue(Onvif.rankLabel(labels.get(i - 1)) < Onvif.rankLabel(labels.get(i)));
        // a camera's own detection sits after the ones we know and before plain motion
        assertTrue(Onvif.rankLabel("Smart event") < Onvif.rankLabel("Face"));
        assertTrue(Onvif.rankLabel("Face") < Onvif.rankLabel("Motion"));
    }

    @Test
    public void namesFlagsWeDontKnow() {
        assertEquals("Person", Onvif.getFlagLabel("IsPeople"));
        assertEquals("Motion", Onvif.getFlagLabel("IsMotion"));
        assertEquals("Face", Onvif.getFlagLabel("IsFace"));
        assertEquals("Face found", Onvif.getFlagLabel("IsFaceFound"));
    }

    @Test
    public void learnsDetectionsACameraDidntList() {
        List<String> offered = Arrays.asList("Person", "Smart event", "Motion");
        assertNull(MotionService.chooseLearnedKinds(offered, Collections.singletonList("Person")));
        assertEquals(Arrays.asList("Person", "Smart event", "Face", "Motion"),
                MotionService.chooseLearnedKinds(offered, Arrays.asList("Face", "Motion")));
        // never asked: every kind we know is offered already, nothing to learn
        assertNull(MotionService.chooseLearnedKinds(Collections.emptyList(), Collections.singletonList("Face")));
    }

    @Test
    public void switchedOffKindsGiveWayToTheNext() {
        List<String> k = Arrays.asList("Person", "Line crossing", "Motion");
        assertEquals("Person", Cam.findFirstKindOn(k, off()));
        assertEquals("Line crossing", Cam.findFirstKindOn(k, off("Person")));
        assertEquals("Motion", Cam.findFirstKindOn(k, off("Person", "Line crossing")));
        assertNull("all off: ignored", Cam.findFirstKindOn(k, off("Person", "Line crossing", "Motion")));
        assertEquals("Person", Cam.findFirstKindOn(k, off("Line crossing"))); // a person crossing the line still alerts
    }

    @Test
    public void quietHoursPastMidnight() {
        int from = 23 * 60, to = 7 * 60;
        assertTrue(Cam.isInQuietHours(23 * 60, from, to));
        assertTrue(Cam.isInQuietHours(2 * 60 + 30, from, to));
        assertTrue(Cam.isInQuietHours(6 * 60 + 59, from, to));
        assertFalse("ends at 07:00", Cam.isInQuietHours(7 * 60, from, to));
        assertFalse(Cam.isInQuietHours(14 * 60, from, to));
        assertFalse(Cam.isInQuietHours(22 * 60 + 59, from, to));
    }

    @Test
    public void quietHoursWithinADay() {
        assertTrue(Cam.isInQuietHours(13 * 60, 12 * 60, 14 * 60));
        assertFalse(Cam.isInQuietHours(14 * 60, 12 * 60, 14 * 60));
        assertFalse(Cam.isInQuietHours(11 * 60, 12 * 60, 14 * 60));
        assertFalse("same start and end: never, not always", Cam.isInQuietHours(5 * 60, 600, 600));
    }

    @Test
    public void quietHoursOnlyMakeItQuieter() {
        assertEquals(Cam.VOICE, Cam.chooseSound(Cam.VOICE, false, Cam.MUTE));
        assertEquals(Cam.MUTE, Cam.chooseSound(Cam.VOICE, true, Cam.MUTE));
        assertEquals(Cam.CHIME, Cam.chooseSound(Cam.VOICE, true, Cam.CHIME));
        assertEquals(Cam.CHIME, Cam.chooseSound(Cam.CHIME, true, Cam.CHIME));
        assertEquals("never louder", Cam.MUTE, Cam.chooseSound(Cam.MUTE, true, Cam.CHIME));
    }

    @Test
    public void hhmm() {
        assertEquals("07:00", Cam.formatClock(7 * 60));
        assertEquals("23:30", Cam.formatClock(23 * 60 + 30));
    }

    @Test
    public void personGoesAheadOfWaitingAnnouncements() {
        List<kotlin.Pair<String, String>> waitingAnnouncements = new java.util.ArrayList<>();
        Announcer.addAnnouncementInLine(waitingAnnouncements, "Vehicle", "v");
        Announcer.addAnnouncementInLine(waitingAnnouncements, "Motion", "m");
        Announcer.addAnnouncementInLine(waitingAnnouncements, "Person", "p1");
        Announcer.addAnnouncementInLine(waitingAnnouncements, "Person", "p2");
        Announcer.addAnnouncementInLine(waitingAnnouncements, "Tamper", "t");
        StringBuilder spokenOrder = new StringBuilder();
        for (kotlin.Pair<String, String> announcement : waitingAnnouncements) {
            spokenOrder.append(announcement.getSecond()).append(' ');
        }
        assertEquals("p1 p2 v m t ", spokenOrder.toString());
    }

    private static Cam camAt(String host, String id) {
        Cam c = new Cam();
        c.host = host;
        c.deviceId = id;
        c.eventsUrl = "http://" + host.split(":")[0] + ":2020/onvif/service";
        c.streamUrlHd = "rtsp://" + host.split(":")[0] + ":554/stream1";
        c.streamUrlSd = "rtsp://u:p@" + host.split(":")[0] + ":554/stream2"; // a login in the address too
        return c;
    }

    @Test
    public void findsACameraAgainAfterItsAddressChanges() {
        Cam moved = camAt("192.168.1.52", "urn:uuid:aaa");
        Cam unchanged = camAt("192.168.1.60", "urn:uuid:bbb");
        Cam learning = camAt("192.168.1.70", ""); // saved before IDs were learned
        Cam gone = camAt("192.168.1.80", "urn:uuid:ddd"); // not answering discovery at all
        List<kotlin.Triple<String, String, String>> found = Arrays.asList(
                new kotlin.Triple<>("192.168.1.99", "C320WS", "urn:uuid:aaa"),
                new kotlin.Triple<>("192.168.1.60", "C320WS", "urn:uuid:bbb"),
                new kotlin.Triple<>("192.168.1.70:8000", "DS-2CD", "urn:uuid:ccc"));
        List<Cam> result = Cam.applyDiscovery(Arrays.asList(moved, unchanged, learning, gone), found);

        assertEquals(Collections.singletonList(moved), result);
        assertEquals("192.168.1.99", moved.host);
        assertEquals("http://192.168.1.99:2020/onvif/service", moved.eventsUrl);
        assertEquals("rtsp://192.168.1.99:554/stream1", moved.streamUrlHd);
        assertEquals("rtsp://u:p@192.168.1.99:554/stream2", moved.streamUrlSd);
        assertEquals("192.168.1.60", unchanged.host);
        assertEquals("urn:uuid:ccc", learning.deviceId); // learned from the camera at its address
        assertEquals("192.168.1.70", learning.host); // only learned, not moved
        assertEquals("192.168.1.80", gone.host);
    }

    @Test
    public void movingKeepsOtherNumbersInTheAddressesAlone() {
        Cam c = camAt("10.0.0.5", "urn:uuid:x");
        c.streamUrlHd = "rtsp://10.0.0.5:554/10.0.0.5/live"; // a path that happens to contain the IP stays
        c.moveTo("10.0.0.50");
        assertEquals("rtsp://10.0.0.50:554/10.0.0.5/live", c.streamUrlHd);
        assertEquals("10.0.0.50", c.host);
    }
}
