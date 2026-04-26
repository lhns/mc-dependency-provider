package de.lhns.mcdp.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Mixin class as delegating to an implementation class loaded from the mod's own
 * {@code ModClassLoader}. See ADR-0008.
 * <p>
 * The annotated class is a plain Java mixin sitting in Minecraft's transforming classloader; it
 * holds a static reference to an interface and initializes it via
 * {@link McdpProvider#loadMixinImpl(Class)}. The interface is declared under a
 * {@code shared_packages} prefix so its {@code Class} identity is preserved across the mod and
 * game classloaders.
 *
 * <pre>{@code
 * @Mixin(FlowingFluid.class)
 * @McdpMixin(impl = "de.lolhens.fluidphysics.impl.FlowableFluidLogicScala")
 * public abstract class FlowingFluidMixin {
 *     private static final FlowableFluidLogic logic =
 *         McdpProvider.loadMixinImpl(FlowableFluidLogic.class);
 *
 *     @Inject(method = "...", at = @At("HEAD"))
 *     private void delegate(CallbackInfo ci) {
 *         logic.onFlow(this);
 *     }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface McdpMixin {
    /** Fully-qualified class name of the implementation in the mod's classloader. */
    String impl();

    /**
     * Optional mod id override when stack-walking can't identify the caller — defaults to empty,
     * meaning the provider auto-detects the calling mixin's owning mod.
     */
    String modId() default "";
}
