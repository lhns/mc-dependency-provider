package de.lhns.mcdp.fabric;

import de.lhns.mcdp.api.McdpProvider;
import de.lhns.mcdp.core.ModClassLoader;
import de.lhns.mcdp.deps.LibraryCache;
import de.lhns.mcdp.deps.ManifestConsumer;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McdpLanguageAdapter#create} against a {@link ModClassLoader} registered through the
 * public {@link McdpProvider#registerMod} and a proxied {@link ModContainer}.
 *
 * <p>Each test uses its own modId: {@link McdpProvider}'s registries are process-global and its
 * reset hook is package-private to {@code de.lhns.mcdp.api}.
 */
class McdpLanguageAdapterTest {

    /** The entrypoint. Public with a public no-arg ctor, as a real Fabric entrypoint is. */
    public static final class Entry implements Runnable {
        public Entry() {}

        @Override
        public void run() {}
    }

    /** No constructor the Java adapter can fill from Fabric's empty context bag. */
    public static final class NoUsableCtor implements Runnable {
        public NoUsableCtor(String required) {}

        @Override
        public void run() {}
    }

    /**
     * Catches loading the entrypoint through any loader but the mod's own. The mod loader holds
     * its own copy of {@link Entry}'s bytes, and it is child-first for this package, so only a
     * lookup through it yields a class that is not the test's {@code Entry}.
     */
    @Test
    void constructsTheEntrypointThroughTheModsLoader(@TempDir Path tmp) throws Exception {
        String entryResource = Entry.class.getName().replace('.', '/') + ".class";
        Path copy = tmp.resolve(entryResource);
        Files.createDirectories(copy.getParent());
        try (InputStream in = McdpLanguageAdapterTest.class.getClassLoader()
                .getResourceAsStream(entryResource)) {
            Files.write(copy, in.readAllBytes());
        }

        try (ModClassLoader loader = new ModClassLoader("adapter-success-mod",
                new URL[]{tmp.toUri().toURL()}, McdpLanguageAdapterTest.class.getClassLoader(), List.of())) {
            McdpProvider.registerMod("adapter-success-mod", loader);

            Runnable instance = new McdpLanguageAdapter().create(
                    FabricStubs.mod("adapter-success-mod"), Entry.class.getName(), Runnable.class);

            assertEquals(Entry.class.getName(), instance.getClass().getName());
            assertSame(loader, instance.getClass().getClassLoader());
            assertNotSame(Entry.class, instance.getClass());
        }
    }

    /** Pre-launch never ran for this mod (or it has no manifest): a named, wrapped failure. */
    @Test
    void failsNamingTheModWhenNoLoaderIsRegistered() {
        LanguageAdapterException e = assertThrows(LanguageAdapterException.class, () ->
                new McdpLanguageAdapter().create(
                        FabricStubs.mod("adapter-never-registered-mod"), Entry.class.getName(), Runnable.class));

        assertTrue(e.getMessage().startsWith("mcdepprovider:"), e.getMessage());
        assertTrue(e.getMessage().contains("adapter-never-registered-mod"), e.getMessage());
    }

    @Test
    void wrapsAMissingEntrypointClass() throws Exception {
        try (ModClassLoader loader = emptyLoader("adapter-missing-class-mod")) {
            McdpProvider.registerMod("adapter-missing-class-mod", loader);

            LanguageAdapterException e = assertThrows(LanguageAdapterException.class, () ->
                    new McdpLanguageAdapter().create(
                            FabricStubs.mod("adapter-missing-class-mod"), "com.example.DoesNotExist", Runnable.class));

            assertTrue(e.getMessage().startsWith("mcdepprovider:"), e.getMessage());
            assertTrue(e.getMessage().contains("com.example.DoesNotExist"), e.getMessage());
            assertInstanceOf(ClassNotFoundException.class, e.getCause());
        }
    }

    /**
     * Catches dropping the {@code type.isInstance} check: without it the method returns an
     * object of the wrong type through an unchecked cast, and Fabric fails later with a
     * ClassCastException that names neither the entrypoint nor the mod.
     */
    @Test
    void rejectsAnEntrypointOfTheWrongType() throws Exception {
        try (ModClassLoader loader = emptyLoader("adapter-wrong-type-mod")) {
            McdpProvider.registerMod("adapter-wrong-type-mod", loader);

            LanguageAdapterException e = assertThrows(LanguageAdapterException.class, () ->
                    new McdpLanguageAdapter().create(
                            FabricStubs.mod("adapter-wrong-type-mod"), Entry.class.getName(), Comparable.class));

            assertTrue(e.getMessage().startsWith("mcdepprovider:"), e.getMessage());
            assertTrue(e.getMessage().contains(Comparable.class.getName()), e.getMessage());
        }
    }

    @Test
    void wrapsAConstructionFailure() throws Exception {
        try (ModClassLoader loader = emptyLoader("adapter-no-ctor-mod")) {
            McdpProvider.registerMod("adapter-no-ctor-mod", loader);

            LanguageAdapterException e = assertThrows(LanguageAdapterException.class, () ->
                    new McdpLanguageAdapter().create(
                            FabricStubs.mod("adapter-no-ctor-mod"), NoUsableCtor.class.getName(), Runnable.class));

            assertTrue(e.getMessage().startsWith("mcdepprovider:"), e.getMessage());
            assertInstanceOf(NoSuchMethodException.class, e.getCause());
        }
    }

    /**
     * An unknown manifest {@code lang} used to escape as a bare IllegalArgumentException from
     * {@code EntrypointAdapter.forLang}; Fabric expects a LanguageAdapterException, and the
     * crash named neither the mod nor mcdepprovider. The lang reaches the adapter the way it does
     * in production: through pre-launch reading the mod's manifest. The entrypoint is a loadable
     * class so the call gets past {@code Class.forName} to the lang lookup.
     */
    @Test
    void wrapsAnUnsupportedLangNamingTheMod(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("cobol-mod"));
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/mcdepprovider.toml"), "lang = \"cobol\"\n");
        ModContainer mod = FabricStubs.mod("adapter-cobol-mod", root);

        McdpPreLaunch.run(FabricStubs.loader(mod),
                new ManifestConsumer(new LibraryCache(tmp.resolve("cache"))));

        LanguageAdapterException e = assertThrows(LanguageAdapterException.class, () ->
                new McdpLanguageAdapter().create(mod, Entry.class.getName(), Runnable.class));

        assertTrue(e.getMessage().startsWith("mcdepprovider:"), e.getMessage());
        assertTrue(e.getMessage().contains("adapter-cobol-mod"), e.getMessage());
        assertTrue(e.getMessage().contains("cobol"), e.getMessage());
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }

    // --- `Class::member` entrypoints (fabric-loader's DefaultLanguageAdapter semantics) --------

    /**
     * Members for the {@code Class::member} forms. Loaded through the mod's loader from a copy of
     * its bytes, never from the test classpath: each assertion that it came back from the mod
     * loader proves the member's class was looked up there.
     */
    public static final class Members {
        public static final Integer FORTY_TWO = 42;
        private static final Integer HIDDEN = 7;
        public Integer notStatic = 1;
        public static final Integer both = 1;

        private final int seed = 5;

        public Members() {}

        public static int both() {
            return 1;
        }

        public static int answer() {
            return 42;
        }

        public static Object definingLoader() {
            return Members.class.getClassLoader();
        }

        public static int overloaded() {
            return 1;
        }

        public static int overloaded(int x) {
            return x;
        }

        public int seeded() {
            return seed;
        }
    }

    /** Two constructors: {@code ::<init>} must refuse to pick one, as Fabric does. */
    public static final class TwoCtors {
        public TwoCtors() {}

        public TwoCtors(int x) {}
    }

    /** The shape kotlinc gives an {@code object}: a static INSTANCE and a private constructor. */
    public static final class KotlinObject {
        public static final KotlinObject INSTANCE = new KotlinObject();

        private KotlinObject() {}

        public Object self() {
            return this;
        }
    }

    /** The shape scalac gives an {@code object}'s module class: a static MODULE$, private ctor. */
    public static final class ScalaModule {
        public static final ScalaModule MODULE$ = new ScalaModule();

        private ScalaModule() {}

        public Object self() {
            return this;
        }
    }

    @Test
    void aStaticFieldYieldsItsValue(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-field-mod")) {
            Number value = create("adapter-field-mod", Members.class.getName() + "::FORTY_TWO", Number.class);
            assertEquals(42, value);
        }
    }

    @Test
    void aStaticMethodBecomesAProxyOfTheRequestedInterface(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-static-method-mod")) {
            IntSupplier answer = create("adapter-static-method-mod",
                    Members.class.getName() + "::answer", IntSupplier.class);
            assertEquals(42, answer.getAsInt());

            @SuppressWarnings("unchecked")
            Supplier<Object> definingLoader = create("adapter-static-method-mod",
                    Members.class.getName() + "::definingLoader", Supplier.class);
            assertSame(loader, definingLoader.get(), "the member's class must come from the mod's loader");
        }
    }

    /** A plain Java class: an instance method is bound to a new instance, as in Fabric. */
    @Test
    void anInstanceMethodOfAJavaClassIsBoundToANewInstance(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-instance-method-mod")) {
            IntSupplier seeded = create("adapter-instance-method-mod",
                    Members.class.getName() + "::seeded", IntSupplier.class);
            assertEquals(5, seeded.getAsInt());
        }
    }

    /**
     * {@code Foo::<init>} (fabric-loader 0.19+): every call of the proxy constructs a new
     * instance, of the class the mod's loader defines.
     */
    @Test
    void initBecomesAProxyThatConstructsANewInstanceEachCall(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-init-mod")) {
            @SuppressWarnings("unchecked")
            Supplier<Object> ctor = create("adapter-init-mod", Members.class.getName() + "::<init>", Supplier.class);
            Object first = ctor.get();
            Object second = ctor.get();

            assertSame(loader, first.getClass().getClassLoader());
            assertEquals(Members.class.getName(), first.getClass().getName());
            assertNotSame(first, second);
        }
    }

    /**
     * The point of binding through {@code EntrypointAdapter}: a Kotlin {@code object}'s method
     * must run on {@code INSTANCE}. Fabric's own {@code new Foo()} fails on the private
     * constructor; forcing it open would bind to a second, unused instance.
     */
    @Test
    void aKotlinObjectsInstanceMethodIsBoundToInstance(@TempDir Path tmp) throws Exception {
        ModContainer mod = langMod(tmp, "adapter-kotlin-object-mod", "kotlin", KotlinObject.class);

        @SuppressWarnings("unchecked")
        Supplier<Object> self = new McdpLanguageAdapter().create(
                mod, KotlinObject.class.getName() + "::self", Supplier.class);

        Class<?> modSide = McdpProvider.loaderFor("adapter-kotlin-object-mod").loadClass(KotlinObject.class.getName());
        assertNotSame(KotlinObject.class, modSide);
        assertSame(modSide.getField("INSTANCE").get(null), self.get());
    }

    /** Same for a Scala {@code object}: its method runs on {@code MODULE$}. */
    @Test
    void aScalaObjectsInstanceMethodIsBoundToModule(@TempDir Path tmp) throws Exception {
        ModContainer mod = langMod(tmp, "adapter-scala-object-mod", "scala", ScalaModule.class);

        @SuppressWarnings("unchecked")
        Supplier<Object> self = new McdpLanguageAdapter().create(
                mod, ScalaModule.class.getName() + "::self", Supplier.class);

        Class<?> modSide = McdpProvider.loaderFor("adapter-scala-object-mod").loadClass(ScalaModule.class.getName());
        assertNotSame(ScalaModule.class, modSide);
        assertSame(modSide.getField("MODULE$").get(null), self.get());
    }

    @Test
    void moreThanOneSeparatorIsAnInvalidHandle(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-bad-handle-mod")) {
            assertFails("adapter-bad-handle-mod", Members.class.getName() + "::answer::more", IntSupplier.class,
                    "Invalid handle format: " + Members.class.getName() + "::answer::more");
        }
    }

    /** Used to report "entrypoint class not found: com.example.Missing::run". */
    @Test
    void aMissingClassIsNamedWithoutTheMember(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-member-missing-class-mod")) {
            LanguageAdapterException e = assertFails("adapter-member-missing-class-mod",
                    "com.example.Missing::run", Runnable.class, "entrypoint class not found: com.example.Missing");
            assertTrue(!e.getMessage().contains("Missing::run"), e.getMessage());
            assertInstanceOf(ClassNotFoundException.class, e.getCause());
        }
    }

    @Test
    void aNonStaticFieldIsRejected(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-instance-field-mod")) {
            assertFails("adapter-instance-field-mod", Members.class.getName() + "::notStatic", Number.class,
                    "Field " + Members.class.getName() + "::notStatic must be static!");
        }
    }

    @Test
    void aFieldAndAMethodSharingTheNameAreAmbiguous(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-ambiguous-mod")) {
            assertFails("adapter-ambiguous-mod", Members.class.getName() + "::both", Number.class,
                    "Ambiguous " + Members.class.getName() + "::both - refers to both field and method!");
        }
    }

    @Test
    void aFieldOfTheWrongTypeIsRejected(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-field-type-mod")) {
            assertFails("adapter-field-type-mod", Members.class.getName() + "::FORTY_TWO", Runnable.class,
                    "Field " + Members.class.getName() + "::FORTY_TWO cannot be cast to java.lang.Runnable!");
        }
    }

    @Test
    void anInaccessibleFieldIsRejected(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-private-field-mod")) {
            LanguageAdapterException e = assertFails("adapter-private-field-mod",
                    Members.class.getName() + "::HIDDEN", Number.class,
                    "Field " + Members.class.getName() + "::HIDDEN cannot be accessed!");
            assertInstanceOf(IllegalAccessException.class, e.getCause());
        }
    }

    @Test
    void aMethodCannotBeProxiedToAClass(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-proxy-class-mod")) {
            assertFails("adapter-proxy-class-mod", Members.class.getName() + "::answer", Number.class,
                    "Cannot proxy method " + Members.class.getName() + "::answer to non-interface type java.lang.Number!");
        }
    }

    @Test
    void anUnknownMemberIsNotFound(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-no-member-mod")) {
            assertFails("adapter-no-member-mod", Members.class.getName() + "::nope", Runnable.class,
                    "Could not find " + Members.class.getName() + "::nope!");
        }
    }

    @Test
    void overloadsAreRejected(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-overload-mod")) {
            assertFails("adapter-overload-mod", Members.class.getName() + "::overloaded", IntSupplier.class,
                    "Found multiple method entries of name " + Members.class.getName() + "::overloaded!");
        }
    }

    @Test
    void initWithSeveralConstructorsIsRejected(@TempDir Path tmp) throws Exception {
        try (ModClassLoader loader = membersMod(tmp, "adapter-two-ctors-mod")) {
            assertFails("adapter-two-ctors-mod", TwoCtors.class.getName() + "::<init>", Supplier.class,
                    "Found multiple method entries of name " + TwoCtors.class.getName() + "::<init>!");
        }
    }

    // --- helpers ---

    private static ModClassLoader emptyLoader(String modId) {
        return new ModClassLoader(modId, new URL[0], McdpLanguageAdapterTest.class.getClassLoader(), List.of());
    }

    /**
     * A java-lang mod whose loader holds its own copies of the member fixtures, registered
     * directly (lang defaults to java when pre-launch never saw the mod).
     */
    private static ModClassLoader membersMod(Path tmp, String modId) throws Exception {
        Path root = copyClasses(tmp.resolve(modId), Members.class, TwoCtors.class);
        ModClassLoader loader = new ModClassLoader(modId, new URL[]{root.toUri().toURL()},
                McdpLanguageAdapterTest.class.getClassLoader(), List.of());
        McdpProvider.registerMod(modId, loader);
        return loader;
    }

    /**
     * A mod with the given manifest {@code lang}, registered the way production does it: through
     * pre-launch, which is the only writer of the lang the adapter reads.
     */
    private static ModContainer langMod(Path tmp, String modId, String lang, Class<?>... classes) throws Exception {
        Path root = copyClasses(tmp.resolve(modId), classes);
        Files.createDirectories(root.resolve("META-INF"));
        Files.writeString(root.resolve("META-INF/mcdepprovider.toml"), "lang = \"" + lang + "\"\n");
        ModContainer mod = FabricStubs.mod(modId, root);
        McdpPreLaunch.run(FabricStubs.loader(mod), new ManifestConsumer(new LibraryCache(tmp.resolve("cache"))));
        return mod;
    }

    private static Path copyClasses(Path root, Class<?>... classes) throws Exception {
        for (Class<?> c : classes) {
            String resource = c.getName().replace('.', '/') + ".class";
            Path copy = root.resolve(resource);
            Files.createDirectories(copy.getParent());
            try (InputStream in = McdpLanguageAdapterTest.class.getClassLoader().getResourceAsStream(resource)) {
                Files.write(copy, in.readAllBytes());
            }
        }
        return root;
    }

    private static <T> T create(String modId, String value, Class<T> type) throws LanguageAdapterException {
        return new McdpLanguageAdapter().create(FabricStubs.mod(modId), value, type);
    }

    /** Asserts a named, prefixed LanguageAdapterException carrying Fabric's own wording. */
    private static LanguageAdapterException assertFails(String modId, String value, Class<?> type, String fabricMessage) {
        LanguageAdapterException e = assertThrows(LanguageAdapterException.class, () -> create(modId, value, type));
        assertTrue(e.getMessage().startsWith("mcdepprovider: "), e.getMessage());
        assertTrue(e.getMessage().contains(fabricMessage), e.getMessage());
        assertTrue(e.getMessage().contains("'" + modId + "'"), e.getMessage());
        return e;
    }
}
