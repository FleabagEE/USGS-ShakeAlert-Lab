# Java Receiver Build Reproducibility

The Scenario OpenWire receiver uses Java 21 and a repository-defined Maven
build. Maven dependencies and plugins belong only in `.mvn/repository`; this
directory is generated local state and must not be committed. Normal build and
test actions run Maven offline through `bin/java-receiver`.

## Authorized one-time provisioning

Provisioning is a separate, explicitly authorized operation because it may use
the network. Install the approved Maven `3.8.7` package and populate the empty
isolated repository with the POM's pinned dependencies and plugins. Do not use
JARs from `/tmp`, backup trees, Downloads, or legacy ShakeAlert bundles.

After provisioning, generate and review the lock manifest:

```bash
build-support/generate-maven-checksums
git status --short build-support/maven-artifacts.sha256
sha256sum build-support/maven-artifacts.sha256
```

The final `build-support/maven-artifacts.sha256` was generated after reviewing
the dependency tree, plugin list, sources, and artifact provenance. The
repository-local cache remains ignored; the manifest is the committed lock and
integrity record.

## Offline validation

```bash
build-support/verify-maven-checksums
bin/java-receiver test
mvn -o dependency:tree -Dverbose -DoutputFile=target/dependency-tree.txt
mvn -o verify
bin/java-receiver verify-runtime
```

The final `cd8e55c` pre-deployment verification used Ubuntu Maven 3.8.7 and
OpenJDK 21.0.11. The current suite discovers 97 JUnit tests; 96 run in the
ordinary offline suite and one historical-corpus regression is opt-in when its
approved local capture sources are not supplied. The frozen historical corpus
has separately passed at 28/28. Maven Enforcer and duplicate-class checks
passed, and two clean offline builds produced the same packaged-JAR SHA-256.

`verify-runtime` inspects only compiled files and the generated classpath. It
does not load `ScenarioOpenWireReceiver`, read credentials, or establish a JMS,
TLS, TCP, or other network connection.
