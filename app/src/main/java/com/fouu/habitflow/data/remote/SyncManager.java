package com.fouu.habitflow.data.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.fouu.habitflow.data.db.AppDatabase;
import com.fouu.habitflow.data.db.AchievementDao;
import com.fouu.habitflow.data.db.FocusSessionDao;
import com.fouu.habitflow.data.db.HabitDao;
import com.fouu.habitflow.data.db.HabitLogDao;
import com.fouu.habitflow.data.model.Achievement;
import com.fouu.habitflow.data.model.FocusSession;
import com.fouu.habitflow.data.model.Habit;
import com.fouu.habitflow.data.model.HabitLog;
import com.fouu.habitflow.util.PreferenceManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SyncManager - Cloud backup & cross-device sync via Firebase Firestore.
 *
 * Strategy:
 * - Local Room is the source of truth for the running device.
 * - On every local mutation (insert/update/delete of habit/log/focus/achievement) we mark
 *   the affected collection "dirty" and schedule a push.
 * - On login (or app start while signed in) we PULL remote data first (to restore after
 *   reinstall), merge by localId + updatedAt (last-write-wins), then PUSH local delta.
 * - If offline, dirty flags are persisted to SharedPreferences and retried on next sync.
 *
 * Firestore layout:  users/{uid}/habits/{localId}, .../habit_logs/{localId},
 *                    .../focus_sessions/{clientId}, .../achievements/{localId}
 */
public class SyncManager {

    private static final String TAG = "SyncManager";

    // Dirty flags persisted across restarts
    private static final String PREF_SYNC_DIRTY = "sync_dirty_set";

    // Firestore collection names per entity
    static final String COL_HABITS = "habits";
    static final String COL_LOGS = "habit_logs";
    static final String COL_FOCUS = "focus_sessions";
    static final String COL_ACHIEVEMENTS = "achievements";

    private static SyncManager instance;

    private final Context appContext;
    private final FirebaseFirestore firestore;
    private final PreferenceManager prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Daos (lazy via AppDatabase)
    private HabitDao habitDao;
    private HabitLogDao habitLogDao;
    private FocusSessionDao focusSessionDao;
    private AchievementDao achievementDao;

    private boolean stopped = false;

    private SyncManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.firestore = FirebaseFirestore.getInstance();
        this.prefs = PreferenceManager.getInstance(appContext);
    }

    public static synchronized SyncManager getInstance(@NonNull Context context) {
        if (instance == null) {
            instance = new SyncManager(context.getApplicationContext());
        }
        return instance;
    }

    private void ensureDaos() {
        if (habitDao == null) {
            AppDatabase db = AppDatabase.getInstance(appContext);
            habitDao = db.habitDao();
            habitLogDao = db.habitLogDao();
            focusSessionDao = db.focusSessionDao();
            achievementDao = db.achievementDao();
        }
    }

    private String currentUid() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? user.getUid() : null;
    }

    /** Stop all sync work (e.g. on sign-out). */
    public void stop() {
        stopped = true;
    }

    /** Resume sync (e.g. on sign-in). */
    public void resume() {
        stopped = false;
    }

    /**
     * Permanently delete all of the given user's remote backup data — every document in the
     * 4 collections under users/{uid}. Called when the user signs out / deletes their account,
     * so the cloud backup is wiped (matching the privacy policy: "deleting your account removes
     * your cloud data"). Runs on the background executor and invokes onComplete on the main
     * thread when finished.
     *
     * Intentionally IGNORES the {@code stopped} flag: this is an explicit, intentional deletion,
     * not routine sync work, and must run even right after {@link #stop()} was called.
     */
    public void deleteAccountData(String uid, Runnable onComplete) {
        if (uid == null) { postToMain(onComplete); return; }
        executor.execute(() -> {
            for (String col : new String[]{COL_HABITS, COL_LOGS, COL_FOCUS, COL_ACHIEVEMENTS}) {
                try {
                    QuerySnapshot snap = Tasks.await(
                            firestore.collection("users").document(uid).collection(col).get());
                    if (!snap.isEmpty()) {
                        WriteBatch b = firestore.batch();
                        for (DocumentSnapshot d : snap.getDocuments()) b.delete(d.getReference());
                        Tasks.await(b.commit());
                    }
                    Log.d(TAG, "deleted remote " + col + " for uid=" + uid + " (" + snap.size() + " docs)");
                } catch (Exception e) {
                    Log.w(TAG, "delete remote " + col + " failed: " + e.getMessage());
                }
            }
            postToMain(onComplete);
        });
    }

    // ===== Dirty flag persistence =====

    private Set<String> readDirty() {
        return new HashSet<>(prefs.getPrefs().getStringSet(PREF_SYNC_DIRTY, new HashSet<>()));
    }

    private void writeDirty(Set<String> dirty) {
        prefs.getPrefs().edit().putStringSet(PREF_SYNC_DIRTY, dirty).apply();
    }

    private void markDirty(String collection) {
        Set<String> dirty = readDirty();
        dirty.add(collection);
        writeDirty(dirty);
    }

    private void clearDirty(String collection) {
        Set<String> dirty = readDirty();
        if (dirty.remove(collection)) writeDirty(dirty);
    }

    // ===== Public API: notify local mutation =====

    public void notifyHabitChanged() {
        ensureDaos();
        markDirty(COL_HABITS);
        schedulePush();
    }

    public void notifyLogChanged() {
        ensureDaos();
        markDirty(COL_LOGS);
        schedulePush();
    }

    public void notifyFocusChanged() {
        ensureDaos();
        markDirty(COL_FOCUS);
        schedulePush();
    }

    public void notifyAchievementChanged() {
        ensureDaos();
        markDirty(COL_ACHIEVEMENTS);
        schedulePush();
    }

    // ===== Full sync on login / app start =====

    /**
     * Perform a full sync: pull remote first (restore after reinstall), then push local delta.
     * Safe to call repeatedly. No-op if not signed in or stopped.
     */
    public void syncNow() {
        executor.execute(this::doSync);
    }

    /**
     * Force a full sync that pushes ALL 4 collections regardless of dirty flags.
     * Use this when the user taps "Sync Now" manually — guarantees nothing is missed
     * (e.g. logs toggled before login, or stale dirty flags that were cleared early).
     */
    public void syncNowForceAll() {
        executor.execute(() -> {
            // Mark everything dirty so pushAll() won't skip any collection.
            markDirty(COL_HABITS);
            markDirty(COL_LOGS);
            markDirty(COL_FOCUS);
            markDirty(COL_ACHIEVEMENTS);
            doSync();
        });
    }

    private void doSync() {
        if (stopped) return;
        String uid = currentUid();
        if (uid == null) {
            Log.d(TAG, "sync skipped: no signed-in user");
            return;
        }
        ensureDaos();
        Log.d(TAG, "sync started for uid=" + uid);
        prefs.setLastSyncError(""); // clear previous error at start of a fresh sync
        pullAll(uid, () -> {
            // Pull complete → now push local delta
            Log.d(TAG, "all pulls done (uid=" + uid + "), pushing local delta");
            pushAll(uid);
            prefs.setLastSyncTime(System.currentTimeMillis());
            Log.d(TAG, "sync finished for uid=" + uid);
            // Notify UI (e.g. Settings) to refresh sync status / error text.
            final Runnable cb = syncCompleteCallback;
            if (cb != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(cb);
            }
        });
    }

    // Optional callback fired on the main thread when a full sync completes,
    // so UI surfaces the latest sync status / error.
    private Runnable syncCompleteCallback;
    public void setSyncCompleteCallback(Runnable r) { this.syncCompleteCallback = r; }

    // ===== PULL: remote -> local (merge last-write-wins) =====

    private void pullAll(String uid, Runnable onPullComplete) {
        final int[] pending = {4}; // 4 collections
        java.util.concurrent.atomic.AtomicBoolean pullFailed = new java.util.concurrent.atomic.AtomicBoolean(false);
        Log.d(TAG, "pullAll started for uid=" + uid);

        for (String col : new String[]{COL_HABITS, COL_LOGS, COL_FOCUS, COL_ACHIEVEMENTS}) {
            Class<?> clazz;
            switch (col) {
                case COL_HABITS: clazz = Habit.class; break;
                case COL_LOGS:  clazz = HabitLog.class; break;
                case COL_FOCUS: clazz = FocusSession.class; break;
                default:         clazz = Achievement.class; break;
            }
            final String collection = col;
            // IMPORTANT: run the Firestore callback on the background executor, not the
            // main thread. mergeRemote() does synchronous Room writes, and pushAll() does
            // synchronous Room reads — both would throw IllegalStateException ("Cannot access
            // database on the main thread") if executed on the UI thread, silently dropping
            // the pulled data.
            firestore.collection("users").document(uid).collection(collection)
                    .get()
                    .addOnSuccessListener(executor, query -> {
                        if (stopped) return;
                        int merged = 0, failed = 0;
                        for (DocumentSnapshot doc : query.getDocuments()) {
                            try {
                                mergeRemote(doc, collection, clazz);
                                merged++;
                            } catch (Exception e) {
                                failed++;
                                Log.w(TAG, "merge " + collection + " doc " + doc.getId() + " failed: " + e.getMessage());
                            }
                        }
                        Log.d(TAG, "pull " + collection + ": " + merged + " merged, " + failed + " failed (total docs=" + query.size() + ")");
                        if (failed > 0) pullFailed.set(true);
                        synchronized (pending) {
                            if (--pending[0] == 0) {
                                if (pullFailed.get()) {
                                    prefs.setLastSyncError("部分数据拉取失败，请检查网络后重试");
                                }
                                onPullComplete.run();
                            }
                        }
                    })
                    .addOnFailureListener(executor, e -> {
                        String msg = "拉取" + collection + "失败: " + e.getMessage();
                        Log.w(TAG, msg);
                        prefs.setLastSyncError(msg);
                        pullFailed.set(true);
                        synchronized (pending) {
                            if (--pending[0] == 0) {
                                onPullComplete.run();
                            }
                        }
                    });
        }
    }

    private void mergeRemote(DocumentSnapshot doc, String collection, Class<?> clazz) {
        try {
            Map<String, Object> data = doc.getData();
            if (data == null) return;

            // IMPORTANT: use the Firestore document id as the canonical, stable sync key.
            // Never rely on the in-document `localId`/`clientId` field alone: legacy docs
            // that lack it would fall back to a freshly generated random UUID on every pull
            // (getLocalId()/getClientId() lazily generate one), so the local lookup always
            // misses and we insert a brand-new duplicate row each sync → "items multiply on
            // every app open". doc.getId() is always stable per document.
            // If no row matches by the stable key, we further reconcile by a CONTENT signature
            // so re-pulling legacy/mismatched rows updates the existing row (adopting docId as
            // its stable key) instead of inserting yet another duplicate. This is what makes a
            // pull idempotent even when local rows still carry stale/random localIds.
            String docId = doc.getId();

            if (collection.equals(COL_HABITS)) {
                Habit remote = new Habit();
                remote.applyFromMap(data);
                remote.setLocalId(docId);
                Log.d(TAG, "merge habit: localId=" + remote.getLocalId() + " name=" + remote.getName()
                        + " updatedAt=" + remote.getUpdatedAt());
                Habit local = habitDao.getHabitByLocalIdSync(remote.getLocalId());
                if (local == null) local = findLocalHabitBySignature(remote);
                if (local == null) {
                    habitDao.insert(remote);
                } else {
                    remote.setId(local.getId());
                    if (remote.getUpdatedAt() >= local.getUpdatedAt()) {
                        habitDao.update(remote); // adopt docId + latest content
                    } else {
                        // Local is newer: just adopt the stable doc id so future pulls match.
                        local.setLocalId(docId);
                        habitDao.update(local);
                    }
                }
            } else if (collection.equals(COL_LOGS)) {
                HabitLog remote = new HabitLog();
                remote.applyFromMap(data);
                remote.setLocalId(docId);
                HabitLog local = habitLogDao.getLogByLocalId(remote.getLocalId());
                if (local == null) local = findLocalLogBySignature(remote);
                if (local == null) {
                    habitLogDao.insert(remote);
                } else {
                    remote.setId(local.getId());
                    if (remote.getUpdatedAt() >= local.getUpdatedAt()) {
                        habitLogDao.update(remote);
                    } else {
                        local.setLocalId(docId);
                        habitLogDao.update(local);
                    }
                }
            } else if (collection.equals(COL_FOCUS)) {
                FocusSession remote = new FocusSession();
                remote.applyFromMap(data);
                remote.setClientId(docId);
                FocusSession local = focusSessionDao.getByClientId(remote.getClientId());
                if (local == null) local = findLocalFocusBySignature(remote);
                if (local == null) {
                    focusSessionDao.insert(remote);
                } else {
                    remote.setId(local.getId());
                    if (remote.getUpdatedAt() >= local.getUpdatedAt()) {
                        focusSessionDao.update(remote);
                    } else {
                        local.setClientId(docId);
                        focusSessionDao.update(local);
                    }
                }
            } else if (collection.equals(COL_ACHIEVEMENTS)) {
                Achievement remote = new Achievement();
                remote.applyFromMap(data);
                remote.setLocalId(docId);
                Achievement local = achievementDao.getByLocalId(remote.getLocalId());
                if (local == null) local = findLocalAchievementBySignature(remote);
                if (local == null) {
                    achievementDao.insert(remote);
                } else {
                    remote.setId(local.getId());
                    if (remote.getUpdatedAt() >= local.getUpdatedAt()) {
                        achievementDao.update(remote);
                    } else {
                        local.setLocalId(docId);
                        achievementDao.update(local);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "merge remote " + collection + " failed: " + e.getMessage());
        }
    }

    // ===== PUSH: local -> remote (only dirty collections) =====

    private void pushAll(String uid) {
        Set<String> dirty = readDirty();
        if (dirty.isEmpty()) {
            Log.d(TAG, "push skipped: nothing dirty");
            return;
        }
        for (String collection : dirty) {
            pushCollection(uid, collection);
        }
    }

    private void pushCollection(String uid, String collection) {
        List<Map<String, Object>> docs = new ArrayList<>();
        java.util.Set<String> localKeys = new java.util.HashSet<>();
        try {
            if (collection.equals(COL_HABITS)) {
                // Push ALL habits incl. soft-deleted (is_archived=1): otherwise a deletion
                // never reaches the cloud and the habit "resurrects" on other devices /
                // after reinstall. The archive state is carried in Habit.toMap().
                for (Habit h : habitDao.getAllHabitsIncludingArchivedSync()) {
                    // Persist a stable sync key if missing, so we never generate a NEW
                    // UUID on each push (that would create duplicate server documents
                    // and make items multiply on every app open).
                    if (!h.hasLocalId()) {
                        h.setLocalId(java.util.UUID.randomUUID().toString());
                        habitDao.update(h);
                    }
                    docs.add(h.toMap());
                    localKeys.add(h.getLocalId());
                }
            } else if (collection.equals(COL_LOGS)) {
                for (HabitLog l : habitLogDao.getAllSync()) {
                    docs.add(l.toMap());
                    localKeys.add(l.getLocalId());
                }
            } else if (collection.equals(COL_FOCUS)) {
                for (FocusSession f : focusSessionDao.getAllSync()) {
                    docs.add(f.toMap());
                    localKeys.add(f.getClientId());
                }
            } else if (collection.equals(COL_ACHIEVEMENTS)) {
                for (Achievement a : achievementDao.getAllSync()) {
                    docs.add(a.toMap());
                    localKeys.add(a.getLocalId());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "read local " + collection + " failed: " + e.getMessage());
            return;
        }

        WriteBatch batch = firestore.batch();
        int sets = 0;
        for (Map<String, Object> doc : docs) {
            Object key = doc.get("localId");
            if (key == null) key = doc.get("clientId");
            if (key == null) continue;
            batch.set(firestore.collection("users").document(uid).collection(collection).document(String.valueOf(key)), doc);
            sets++;
        }

        // Reconcile deletions: any remote document whose stable key is no longer present
        // locally must be deleted remotely. Otherwise deleting a habit locally leaves a
        // zombie on the server that gets pulled back on the next sync. If the remote read
        // fails (e.g. offline) we still commit the local upserts below.
        try {
            QuerySnapshot remote = Tasks.await(
                    firestore.collection("users").document(uid).collection(collection).get());
            int deletes = 0;
            // SAFETY: if NONE of our local keys match ANY remote document id, our local
            // sync keys are disjoint from the server (e.g. local habit localIds were lost /
            // mismatched after a DB rebuild, or a pull failed to adopt remote doc ids). In
            // that case deleting every remote doc would WIPE the user's cloud data — so we
            // skip the delete-reconcile entirely and keep the server intact (a few stale
            // "zombie" docs are far better than mass data loss). Normal deletions (where most
            // keys DO match) still proceed.
            java.util.Set<String> remoteIds = new java.util.HashSet<>();
            for (DocumentSnapshot d : remote.getDocuments()) remoteIds.add(d.getId());
            boolean anyKeyMatches = false;
            for (String k : localKeys) {
                if (remoteIds.contains(k)) { anyKeyMatches = true; break; }
            }
            if (!remoteIds.isEmpty() && !anyKeyMatches) {
                Log.w(TAG, "push " + collection + ": local keys disjoint from remote ids — "
                        + "SKIP delete-reconcile to avoid wiping cloud data (localKeys="
                        + localKeys + ", remoteIds=" + remoteIds + ")");
            } else {
                for (DocumentSnapshot d : remote.getDocuments()) {
                    if (!localKeys.contains(d.getId())) {
                        batch.delete(d.getReference());
                        deletes++;
                    }
                }
            }
            Log.d(TAG, "push " + collection + ": set=" + sets + ", delete=" + deletes);
        } catch (Exception e) {
            Log.w(TAG, "push " + collection + " delete-reconcile skipped: " + e.getMessage());
        }

        try {
            Tasks.await(batch.commit());
            clearDirty(collection);
        } catch (Exception e) {
            String msg = "push " + collection + " failed: " + e.getMessage();
            Log.w(TAG, msg);
            prefs.setLastSyncError(msg);
        }
    }

    /**
     * Reset cloud sync: collapse local duplicates (same sync key), wipe the user's
     * remote collections, then re-upload the clean local data. Use this to recover from
     * the "items multiply on every app open" bug (caused by unstable document ids).
     */
    public void resetCloudSync(Runnable onDone) {
        executor.execute(() -> {
            String uid = currentUid();
            if (uid == null) {
                postToMain(onDone);
                return;
            }
            ensureDaos();
            dedupeLocalByKey();
            // Wipe remote collections FIRST, and wait for each delete to finish before moving on.
            // Otherwise the async delete could commit after the re-upload and wipe the freshly
            // pushed documents (they share the same doc ids), leaving the cloud empty.
            for (String col : new String[]{COL_HABITS, COL_LOGS, COL_FOCUS, COL_ACHIEVEMENTS}) {
                try {
                    QuerySnapshot snap = Tasks.await(
                            firestore.collection("users").document(uid).collection(col).get());
                    if (!snap.isEmpty()) {
                        WriteBatch b = firestore.batch();
                        for (DocumentSnapshot d : snap.getDocuments()) b.delete(d.getReference());
                        Tasks.await(b.commit());
                    }
                    Log.d(TAG, "wiped remote " + col + " (" + snap.size() + " docs)");
                } catch (Exception e) {
                    Log.w(TAG, "wipe " + col + " failed: " + e.getMessage());
                }
            }
            // Re-upload the (now de-duplicated) local data.
            markDirty(COL_HABITS); markDirty(COL_LOGS); markDirty(COL_FOCUS); markDirty(COL_ACHIEVEMENTS);
            pushAll(uid);
            postToMain(onDone);
        });
    }

    /** Always deliver a callback on the main thread (e.g. Toast must not run on a worker thread). */
    private void postToMain(Runnable r) {
        if (r == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
    }

    /**
     * Delete local duplicate habit rows, keeping the most recently updated copy of each.
     *
     * Duplicates created by the old "multiply on every app open" bug each got a DIFFERENT
     * random localId when inserted, so de-duplicating by localId alone does not collapse
     * them. Instead we key by a content signature (name + frequency + targetCount + createdAt)
     * — rows that represent the same logical habit share it. We also assign a stable localId
     * to any row that lacks one so future pushes never re-multiply.
     */
    private void dedupeLocalByKey() {
        List<Habit> habits = habitDao.getAllActiveHabitsSync();
        java.util.Map<String, Habit> best = new java.util.HashMap<>();
        for (Habit h : habits) {
            if (!h.hasLocalId()) {
                h.setLocalId(java.util.UUID.randomUUID().toString());
                habitDao.update(h);
            }
            String sig = habitSignature(h);
            Habit cur = best.get(sig);
            if (cur == null || h.getUpdatedAt() > cur.getUpdatedAt()) best.put(sig, h);
        }
        for (Habit h : habits) {
            Habit b = best.get(habitSignature(h));
            if (b != null && b.getId() != h.getId()) habitDao.delete(h);
        }
    }

    private String habitSignature(Habit h) {
        long created = (h.getCreatedAt() != null) ? h.getCreatedAt().getTime() : 0;
        String name = (h.getName() == null) ? "" : h.getName();
        return name + "|" + h.getFrequency() + "|" + h.getTargetCount() + "|" + created;
    }

    // ----- content-signature lookups (for idempotent pulls, see mergeRemote) -----

    private Habit findLocalHabitBySignature(Habit remote) {
        String sig = habitSignature(remote);
        for (Habit h : habitDao.getAllActiveHabitsSync()) {
            if (sig.equals(habitSignature(h))) return h;
        }
        return null;
    }

    private String logSignature(HabitLog l) {
        long d = (l.getLogDate() != null) ? l.getLogDate().getTime() : 0;
        return l.getHabitId() + "|" + d;
    }

    private HabitLog findLocalLogBySignature(HabitLog remote) {
        String sig = logSignature(remote);
        for (HabitLog l : habitLogDao.getAllSync()) {
            if (sig.equals(logSignature(l))) return l;
        }
        return null;
    }

    private String focusSignature(FocusSession f) {
        long s = (f.getStartTime() != null) ? f.getStartTime().getTime() : 0;
        return String.valueOf(s);
    }

    private FocusSession findLocalFocusBySignature(FocusSession remote) {
        String sig = focusSignature(remote);
        for (FocusSession f : focusSessionDao.getAllSync()) {
            if (sig.equals(focusSignature(f))) return f;
        }
        return null;
    }

    private String achievementSignature(Achievement a) {
        return (a.getTitle() == null) ? "" : a.getTitle();
    }

    private Achievement findLocalAchievementBySignature(Achievement remote) {
        String sig = achievementSignature(remote);
        for (Achievement a : achievementDao.getAllSync()) {
            if (sig.equals(achievementSignature(a))) return a;
        }
        return null;
    }

    /** Lightweight scheduled push for dirty collections (used after local mutations). */
    private void schedulePush() {
        executor.execute(() -> {
            if (stopped) return;
            String uid = currentUid();
            if (uid == null) return; // will retry when syncNow called after login
            ensureDaos();
            Set<String> dirty = readDirty();
            for (String collection : dirty) pushCollection(uid, collection);
        });
    }
}
