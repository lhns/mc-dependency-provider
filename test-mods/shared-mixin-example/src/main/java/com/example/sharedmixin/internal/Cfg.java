package com.example.sharedmixin.internal;

/**
 * Mod-private value type. Every appearance of it inside a mixin has to be reached through a
 * generated bridge: as a return type (shapes 1 and 2), as a constructed instance (shape 3),
 * and as the receiver of {@link #shape()}.
 */
public final class Cfg {

    private final String shape;

    public Cfg(String shape) {
        this.shape = shape;
    }

    public String shape() {
        return shape;
    }
}
