package com.example.mya;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Loads Firebase data for therapist Agent Chat: roster, per-patient briefs, or one patient detail.
 */
public final class TherapistAgentDataLoader {

    private TherapistAgentDataLoader() {}

    public static final class RosterEntry {
        public final String uid;
        public final String displayName;
        public final String firstName;
        public final String lastName;

        public RosterEntry(String uid, String firstName, String lastName) {
            this.uid = uid != null ? uid : "";
            this.firstName = firstName != null ? firstName.trim() : "";
            this.lastName = lastName != null ? lastName.trim() : "";
            String dn = (this.firstName + " " + this.lastName).trim();
            this.displayName = dn.isEmpty() ? this.uid : dn;
        }
    }

    private static DataSnapshot awaitOnce(DatabaseReference ref) throws InterruptedException {
        return awaitQuery(ref);
    }

    private static DataSnapshot awaitQuery(Query ref) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DataSnapshot> box = new AtomicReference<>();
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                box.set(snapshot);
                latch.countDown();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                latch.countDown();
            }
        });
        latch.await(45, TimeUnit.SECONDS);
        return box.get();
    }

    /** Accepted patients under this therapist (same rules as therapist home list). */
    public static List<RosterEntry> loadRosterBlocking(String therapistUid) throws Exception {
        if (therapistUid == null || therapistUid.isEmpty()) return Collections.emptyList();
        DataSnapshot indexSnap = awaitOnce(FirebaseHelper.getTherapistPatientIndexRef(therapistUid));
        if (indexSnap == null || !indexSnap.exists()) return Collections.emptyList();
        List<String> ids = new ArrayList<>();
        for (DataSnapshot ch : indexSnap.getChildren()) {
            if (ch.getKey() != null) ids.add(ch.getKey());
        }
        List<RosterEntry> out = new ArrayList<>();
        for (String pid : ids) {
            DataSnapshot pSnap = awaitOnce(FirebaseHelper.getPatientRef(pid));
            if (pSnap == null || !pSnap.exists()) continue;
            Object at = pSnap.child("assigned_therapist").getValue();
            String tid = at != null ? String.valueOf(at).trim() : "";
            if (!therapistUid.equals(tid)) continue;
            String st = pSnap.child("status").getValue(String.class);
            if (st == null || !"accepted".equalsIgnoreCase(st.trim())) continue;
            String fn = pSnap.child("Name").getValue(String.class);
            String ln = pSnap.child("last_name").getValue(String.class);
            out.add(new RosterEntry(pid, fn != null ? fn : "", ln != null ? ln : ""));
        }
        return out;
    }

    /**
     * If the message clearly names one roster patient, return them; otherwise null (overview / all patients).
     */
    @Nullable
    public static RosterEntry resolveNamedPatient(String userMessage, List<RosterEntry> roster) {
        if (userMessage == null || roster == null || roster.isEmpty()) return null;
        String msg = userMessage.toLowerCase(Locale.getDefault());

        List<RosterEntry> byLongName = new ArrayList<>(roster);
        Collections.sort(byLongName, (a, b) -> Integer.compare(b.displayName.length(), a.displayName.length()));

        for (RosterEntry e : byLongName) {
            String dn = e.displayName.toLowerCase(Locale.getDefault());
            if (dn.length() >= 2 && msg.contains(dn)) return e;
        }

        Map<String, List<RosterEntry>> byFirst = new HashMap<>();
        for (RosterEntry e : roster) {
            String f = e.firstName.toLowerCase(Locale.getDefault());
            if (f.length() < 2) continue;
            byFirst.computeIfAbsent(f, k -> new ArrayList<>()).add(e);
        }
        for (Map.Entry<String, List<RosterEntry>> en : byFirst.entrySet()) {
            if (en.getValue().size() != 1) continue;
            if (msg.contains(en.getKey())) return en.getValue().get(0);
        }
        return null;
    }

    /** Compact facts for every patient (2–3 lines each for the model). */
    public static String buildOverviewDataBlocking(List<RosterEntry> roster) throws Exception {
        if (roster == null || roster.isEmpty()) return "No patients linked to this therapist yet.";
        StringBuilder sb = new StringBuilder();
        for (RosterEntry e : roster) {
            Long count = 0L;
            DataSnapshot cSnap = awaitOnce(FirebaseHelper.getPatientSessionCountRef(e.uid));
            if (cSnap != null && cSnap.exists() && cSnap.getValue() instanceof Number) {
                count = ((Number) cSnap.getValue()).longValue();
            }
            String level = "easy";
            DataSnapshot lSnap = awaitOnce(FirebaseHelper.getPatientSpeechLevelRef(e.uid));
            if (lSnap != null && lSnap.exists() && lSnap.getValue() != null) {
                level = String.valueOf(lSnap.getValue()).trim();
            }
            String lastDate = "";
            Query recent = FirebaseHelper.getPatientSessionRecordsRecent(e.uid, 1);
            DataSnapshot sSnap = awaitQuery(recent);
            if (sSnap != null && sSnap.exists()) {
                for (DataSnapshot ch : sSnap.getChildren()) {
                    Object d = ch.child("date").getValue();
                    if (d != null) lastDate = d.toString();
                    break;
                }
            }
            String idShort = e.uid.length() > 12 ? e.uid.substring(0, 12) + "…" : e.uid;
            sb.append("• ").append(e.displayName).append(" (patientId:").append(idShort).append(")")
                    .append(": sessionCount=").append(count)
                    .append(", speechLevel=").append(level);
            if (!lastDate.isEmpty()) sb.append(", lastSessionDate=").append(lastDate);
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Richer JSON-style facts for one patient (named in chat). */
    public static String buildDetailedPatientDataBlocking(String patientUid) throws Exception {
        JSONObject patient = snapshotToJson(awaitOnce(FirebaseHelper.getPatientRef(patientUid)));
        JSONArray sessions = childrenToJsonArray(awaitQuery(FirebaseHelper.getPatientSessionRecordsRecent(patientUid, 25)));
        JSONArray drills = childrenToJsonArray(awaitQuery(FirebaseHelper.getAssignedDrillsByPatient(patientUid)));
        JSONArray reports = childrenToJsonArray(awaitQuery(FirebaseHelper.getProgressReportsByPatientId(patientUid)));
        return "patient=" + patient.toString()
                + "\nsessions=" + sessions.toString()
                + "\nassigned_drills=" + drills.toString()
                + "\nprogress_reports=" + reports.toString();
    }

    private static JSONObject snapshotToJson(DataSnapshot snap) throws org.json.JSONException {
        JSONObject o = new JSONObject();
        if (snap == null || !snap.exists()) return o;
        for (DataSnapshot c : snap.getChildren()) {
            Object v = c.getValue();
            if (v instanceof Number) o.put(c.getKey(), ((Number) v).doubleValue());
            else if (v instanceof Boolean) o.put(c.getKey(), (Boolean) v);
            else if (v != null) o.put(c.getKey(), v.toString());
        }
        return o;
    }

    private static JSONArray childrenToJsonArray(DataSnapshot snap) throws org.json.JSONException {
        JSONArray arr = new JSONArray();
        if (snap == null || !snap.exists()) return arr;
        for (DataSnapshot c : snap.getChildren()) {
            JSONObject o = new JSONObject();
            for (DataSnapshot cc : c.getChildren()) {
                Object v = cc.getValue();
                if (v instanceof Number) o.put(cc.getKey(), ((Number) v).doubleValue());
                else if (v instanceof Boolean) o.put(cc.getKey(), (Boolean) v);
                else if (v != null) o.put(cc.getKey(), v.toString());
            }
            arr.put(o);
        }
        return arr;
    }
}
