# shared-mixin-example

The one test mod whose `sharedPackages` names **the package its mixins live in**
(`com.example.sharedmixin.mixin`), and whose mixins call mod-private types. That is the
mc-fluid-physics configuration, and until this mod existed nothing in-tree reproduced it:
every other mod shares a dedicated `com.example.api`, and `mixin-example` shares nothing at
all, so ADR-0024's validator A never had a rewritten class to scan and never produced a
non-trivial result in CI.

Two things follow from sharing the mixin package:

- the ADR-0018 codegen rewrites these classes (they carry `@Mixin`), so the generated bridge
  descriptors land back in a class the validator scans; and
- anything the rewriter leaves behind that still names a mod-private type is a build failure
  for a mod that would run clean.

## Why Java

Load-bearing. scalac chains a bridged call straight into the next one and never binds a local,
so a scalac-compiled mixin has no `LocalVariableTable` entry naming the mod-private type —
which is the only reason the LVT bug below has not bitten yet. javac binds locals and emits the
entry. Do not port this mod to Scala.

## The three shapes

Each shape gets its **own mixin class**, not just its own method: a mixin failure takes out the
whole class, so co-locating them would let the first failure mask the rest. All three inject at
`Blocks.<clinit>` HEAD — `<clinit>` is never remapped, so no refmap is needed — and print a
deduplicated marker through the mod-private `SmokeLog`.

| Class | Shape | Marker |
|---|---|---|
| `BridgedCallToLocalMixin` | bridged call, result bound to a local, then used | `[mcdp-smoke] shared-mixin shape=bridged-call-to-local ok` |
| `BridgedCallOnStackMixin` | bridged call consumed directly on the stack | `[mcdp-smoke] shared-mixin shape=bridged-call-on-stack ok` |
| `CtorToLocalMixin` | `new` of a mod-private type into a local, then a call on it | `[mcdp-smoke] shared-mixin shape=ctor-to-local ok` |

Plus the boot marker `[mcdp-smoke] mod=shared_mixin_example boot ok` from the entrypoint.

Shape 2 is the path that already works today; it is here so that a regression in the working
path is visible rather than silently traded for a fix to the other two.

## No negative control here — on purpose

Do not add a direct, unbridged reference to a mod-private type from a shared class "to prove
the validator still fires". That is a true positive, it would fail **this mod's own build**,
and a CI cell whose job is to boot a server cannot also be a build-failure fixture. The
negative control belongs in the plugin's unit tests
(`gradle-plugin/src/test/java/de/lhns/mcdp/gradle/validate/`), where a failure is the assertion.
