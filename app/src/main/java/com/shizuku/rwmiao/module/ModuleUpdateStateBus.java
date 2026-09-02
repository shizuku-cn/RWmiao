package com.shizuku.rwmiao.module;

import java.util.concurrent.CopyOnWriteArrayList;

public final class ModuleUpdateStateBus {
    public enum Kind {
        IDLE,
        CHECKING,
        UP_TO_DATE,
        AVAILABLE,
        FAILED
    }

    public static final class Snapshot {
        public final Kind kind;
        public final String version;
        public final String downloadUrl;
        public final long sizeBytes;
        public final String message;

        private Snapshot(Kind kind, String version, String downloadUrl,
                         long sizeBytes, String message) {
            this.kind = kind;
            this.version = version;
            this.downloadUrl = downloadUrl;
            this.sizeBytes = sizeBytes;
            this.message = message;
        }
    }

    public interface Listener {
        void onUpdateStateChanged(Snapshot state);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();
    private static volatile Snapshot state = new Snapshot(Kind.IDLE, "", "", 0L, "");
    private static boolean started;

    private ModuleUpdateStateBus() {
    }

    public static synchronized boolean begin() {
        if (started) {
            return false;
        }
        started = true;
        return true;
    }

    public static Snapshot snapshot() {
        return state;
    }

    public static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
        listener.onUpdateStateChanged(state);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void checking() {
        publish(new Snapshot(Kind.CHECKING, "", "", 0L, ""));
    }

    public static void upToDate() {
        publish(new Snapshot(Kind.UP_TO_DATE, "", "", 0L, ""));
    }

    public static void available(String version, String downloadUrl, long sizeBytes) {
        publish(new Snapshot(Kind.AVAILABLE, version, downloadUrl, sizeBytes, ""));
    }

    public static void failed(String message) {
        publish(new Snapshot(Kind.FAILED, "", "", 0L, message));
    }

    private static void publish(Snapshot next) {
        state = next;
        for (Listener listener : LISTENERS) {
            listener.onUpdateStateChanged(next);
        }
    }
}
