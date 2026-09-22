package com.example.sharedmixin.internal;

/**
 * Mod-private factory. Deliberately a different class from {@link Cfg}: the call site then
 * needs two bridges — one for the static call's owner, one for the mod-private type it
 * returns — which is the shape mc-fluid-physics has.
 */
public final class Handlers {

    private Handlers() {}

    public static Cfg config(String shape) {
        return new Cfg(shape);
    }
}
