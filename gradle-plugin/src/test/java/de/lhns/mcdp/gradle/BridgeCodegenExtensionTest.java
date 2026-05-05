package de.lhns.mcdp.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link BridgeCodegenExtension} DSL — verifies the conventions set in
 * {@link McdpProviderPlugin} apply, and that {@code crossLoaderAnnotations} is independently
 * configurable from {@code bridgedAnnotations} (ADR-0024).
 */
class BridgeCodegenExtensionTest {

    private Project project;
    private McdpProviderExtension ext;

    @BeforeEach
    void setUp() {
        project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("de.lhns.mcdp");
        ext = (McdpProviderExtension) project.getExtensions().getByName("mcdepprovider");
    }

    @Test
    void defaultCrossLoaderAnnotationsIsMixinOnly() {
        List<String> defaults = ext.getBridges().getCrossLoaderAnnotations().get();
        assertEquals(List.of("org.spongepowered.asm.mixin.Mixin"), defaults);
    }

    @Test
    void defaultBridgedAnnotationsIsMixinAndEventBus() {
        List<String> defaults = ext.getBridges().getBridgedAnnotations().get();
        assertEquals(List.of(
                "org.spongepowered.asm.mixin.Mixin",
                "net.neoforged.bus.api.EventBusSubscriber"
        ), defaults);
    }

    @Test
    void userCanExtendCrossLoaderAnnotationList() {
        // Gradle's ListProperty.convention() is the default; once the user calls add(), it
        // replaces the convention rather than appending. Idiomatic user code therefore reads:
        //   crossLoaderAnnotations.addAll(crossLoaderAnnotations.get()).add(extra)
        // OR uses `convention` itself in their own build (rare). We test the addAll-then-add
        // pattern since that's what users will actually write to extend the defaults.
        var prop = ext.getBridges().getCrossLoaderAnnotations();
        List<String> existing = List.copyOf(prop.get());
        prop.set(existing);
        prop.add("com.example.MyAnno");
        List<String> result = prop.get();
        assertTrue(result.contains("org.spongepowered.asm.mixin.Mixin"));
        assertTrue(result.contains("com.example.MyAnno"));
    }

    @Test
    void userCanReplaceCrossLoaderAnnotationList() {
        ext.getBridges().getCrossLoaderAnnotations().set(List.of("only.X"));
        assertEquals(List.of("only.X"), ext.getBridges().getCrossLoaderAnnotations().get());
    }

    @Test
    void bridgedAndCrossLoaderAreIndependent() {
        // Override one; the other keeps its default.
        ext.getBridges().getCrossLoaderAnnotations().set(List.of("only.X"));
        assertEquals(List.of(
                "org.spongepowered.asm.mixin.Mixin",
                "net.neoforged.bus.api.EventBusSubscriber"
        ), ext.getBridges().getBridgedAnnotations().get());
        assertNotEquals(
                ext.getBridges().getBridgedAnnotations().get(),
                ext.getBridges().getCrossLoaderAnnotations().get());
    }
}
