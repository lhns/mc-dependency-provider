package com.example.mixinmod;

import java.util.concurrent.ConcurrentHashMap;

public final class SmokeLog {

    private static final ConcurrentHashMap<String, Boolean> SEEN = new ConcurrentHashMap<>();

    private SmokeLog() {}

    public static void emit(String mixinName) {
        if (SEEN.putIfAbsent(mixinName, Boolean.TRUE) == null) {
            System.out.println("[mcdp-smoke] mixin=" + mixinName + " ok");
        }
    }
}
