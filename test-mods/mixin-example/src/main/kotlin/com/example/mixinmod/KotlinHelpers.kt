package com.example.mixinmod

/**
 * Kotlin-authored mod-private helper for Phase-4 of the codegen smoke. The mixin's
 * `INVOKESTATIC com/example/mixinmod/KotlinHelpers.onMixinSix()V` call site is bridged the
 * same way as the Java helpers — proving the rewriter is source-language-agnostic in
 * practice (and not just in the unit-test fixtures).
 */
object KotlinHelpers {
    @JvmStatic
    fun onMixinSix() {
        SmokeLog.emit("MinecraftServerSixMixin")
    }
}
