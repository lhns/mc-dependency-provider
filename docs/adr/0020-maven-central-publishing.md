# ADR-0020: Maven Central publishing strategy

**Status:** Accepted — pre-v0.1.0 release-readiness work.

## Context

Through ADR-0016 the project ships one runtime artifact (`de.lhns.mcdp:mcdp` — the merged Fabric+NeoForge jar) plus the Gradle build plugin (`de.lhns.mcdp:gradle-plugin`). Both need to be published somewhere downstream consumers can resolve them. mavenLocal is the only target wired today, which works for tight-loop development but isn't reachable from CI consumers (`mc-fluid-physics`, `test-mods/*` outside this repo's worktree).

The canonical home for OSS JVM libraries is Maven Central. Sonatype's legacy **OSSRH** staging-repository workflow was retired in 2024 in favor of the **Central Portal** (a fully API-driven, deployment-bundle-based upload flow). Tools that haven't migrated still target OSSRH and break on new accounts; tools that have migrated upload one bundle ZIP per release and let the Portal validate POM, signatures, and publication metadata before staging.

Constraints:
- Only `:mcdp` and `:gradle-plugin` should publish. `:fabric`, `:neoforge`, `:core`, `:deps-lib` are internal — they're shaded into `:mcdp` and have no consumer-facing API surface (ADR-0016).
- The CI workflow must be release-triggered, not push-triggered. Accidental main-branch publishes are worse than slow ones.
- Artifacts must be GPG-signed (Central Portal hard requirement).
- Both subprojects must be uploaded as ONE deployment bundle, not separate releases — Central Portal treats one `groupId` ↔ one bundle for atomic staging.

## Decision

### Plugin: `com.vanniktech.maven.publish`

Use the community-maintained `com.vanniktech.maven.publish` Gradle plugin to handle:
- Sources + Javadoc jars (`withSourcesJar()` / `withJavadocJar()` baked in).
- POM defaults + a `pom { … }` DSL for project-specific metadata.
- GPG signing via in-memory key (no on-disk keyring needed; CI passes the armored key as an env var).
- Central Portal staging-bundle upload.

Apply at **root level** with `apply false`, then in each publishable subproject (`:mcdp`, `:gradle-plugin`) so both subprojects share one classloader. Without this, the plugin's `SonatypeRepositoryBuildService` is loaded twice and Gradle rejects the cross-classloader build-service handoff (`Cannot set the value of task '…createStagingRepository' property 'buildService' of type ….SonatypeRepositoryBuildService using a provider of type ….SonatypeRepositoryBuildService`).

### `automaticRelease = false`

Bundles land in Central Portal staging (`https://central.sonatype.com/publishing`). The maintainer reviews and clicks "publish" manually. Avoids accidental releases from a misfired CI run; trades convenience for an audit checkpoint we want during pre-1.0.

### CI workflow: `.github/workflows/publish.yml`

- Trigger: `release: published` (cuts a GitHub release → publishes to staging) plus `workflow_dispatch` for manual reruns.
- Concurrency group `publish-${ref}`, `cancel-in-progress: false` — never cancel an in-flight upload.
- One job: checkout, JDK 21 (Temurin), Gradle home cache, `./gradlew build` (sanity gate), `./gradlew publishToMavenCentral` with credentials in env. `--no-configuration-cache` to keep vanniktech happy.

### Secret namespace

Required:
- `SONATYPE_USERNAME` — Central Portal user-token username (NOT your Sonatype login — generated at https://central.sonatype.com/account)
- `SONATYPE_PASSWORD` — Central Portal user-token password
- `PGP_PRIVATE_KEY` — full ASCII-armored GPG private key block (multi-line; paste the entire `-----BEGIN PGP PRIVATE KEY BLOCK-----` … `-----END PGP PRIVATE KEY BLOCK-----`)

Optional:
- `PGP_KEY_PASSWORD` — only needed if the GPG key is passphrase-protected. The workflow falls back to `''` when the secret is absent (`signingInMemoryKeyPassword` accepts an empty string for unprotected keys). **Recommendation:** generate a CI-only signing subkey with no passphrase and skip this secret entirely.

`PGP_KEY_ID` is also optional (vanniktech only consults it when the keyring contains multiple keys). Since `PGP_PRIVATE_KEY` is a single-key armored block, the secret is unnecessary. Keep this in mind if we later move to a multi-key signing arrangement.

The Gradle property names vanniktech reads (`mavenCentralUsername`, `signingInMemoryKey`, …) are fixed by the plugin; the GitHub secret-name → Gradle-property mapping happens in the workflow's `env:` block via `ORG_GRADLE_PROJECT_<name>` env vars.

## Consequences

**Positive**

- Cutting a GitHub release is the only action needed to ship: the workflow handles bundling, signing, and upload in one run.
- Central Portal staging is the human-review chokepoint. Misfires don't reach consumers.
- Only two artifacts (`:mcdp`, `:gradle-plugin`) — easy mental model for what's published vs internal.
- Single plugin (vanniktech) handles every task in the bundle pipeline. No hand-rolled curl uploads or multi-plugin orchestration.

**Negative**

- Vanniktech is community-maintained, not Sonatype-published. Mitigated by it being widely used (Square, Stripe, Coil, OkHttp ship via vanniktech) and small in surface. If the project goes unmaintained, `com.gradleup.nmcp` is a drop-in narrower alternative.
- The `apply false` + per-subproject apply pattern is non-obvious. Code comment in `build.gradle.kts` calls out the classloader-sharing reason.

## Alternatives

**`com.gradleup.nmcp` (community, narrower scope, Sonatype-doc-referenced).** Single purpose: Central Portal upload. You pair it with `signing` + `maven-publish` + your own POM DSL. Viable; rejected because vanniktech's POM/sources/javadoc auto-config saves boilerplate at the cost of one more plugin dependency. Worth revisiting if vanniktech ever drops support for Central Portal or if we want to minimize external plugin surface.

**`org.jreleaser.jreleaser`.** Multi-channel release tool (Maven + GitHub Releases + Homebrew + Discord). Too broad — we don't need release-note generation or Homebrew-tap pushes. Reconsider when/if we want those.

**Hand-rolled `curl` POST to Central Portal's bundle-upload API.** ~30 lines of shell + manual signing pipeline + manual sources/javadoc generation. Maintenance liability for tooling we'd duplicate from existing plugins.

**Sonatype-published official Gradle plugin.** Doesn't exist for Central Portal. Sonatype ships `central-publishing-maven-plugin` (Maven) but for Gradle they direct users to community options. Nothing to switch to.

**`automaticRelease = true`.** Skips the staging-review checkpoint. Rejected for now — pre-1.0, we want every release to pass through human review. Revisit post-1.0 once the workflow has a track record.

**Publish all subprojects (`:fabric`, `:neoforge`, `:core`, `:deps-lib`) individually.** Rejected per ADR-0016: those are internal, shaded into `:mcdp`, and have no consumer-facing API surface of their own.
