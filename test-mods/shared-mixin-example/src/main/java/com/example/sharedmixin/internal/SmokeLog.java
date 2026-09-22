package com.example.sharedmixin.internal;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Mod-private, deduplicated marker sink — same contract as {@code mixin-example}'s SmokeLog,
 * keyed by shape so CI can assert each of the three call-site shapes independently.
 */
public final class SmokeLog {

    private static final ConcurrentHashMap<String, Boolean> SEEN = new ConcurrentHashMap<>();

    private SmokeLog() {}

    public static void emit(String shape) {
        if (SEEN.putIfAbsent(shape, Boolean.TRUE) == null) {
            System.out.println("[mcdp-smoke] shared-mixin shape=" + shape + " ok");
        }
    }
}
