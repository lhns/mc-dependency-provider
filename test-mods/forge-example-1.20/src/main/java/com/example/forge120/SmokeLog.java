package com.example.forge120;

import java.util.concurrent.ConcurrentHashMap;

/** Deduplicating marker sink — the CI assert step greps for these lines. */
public final class SmokeLog {

    private static final ConcurrentHashMap<String, Boolean> SEEN = new ConcurrentHashMap<>();

    private SmokeLog() {}

    public static void emit(String marker) {
        if (SEEN.putIfAbsent(marker, Boolean.TRUE) == null) {
            System.out.println("[mcdp-smoke] " + marker);
        }
    }
}
