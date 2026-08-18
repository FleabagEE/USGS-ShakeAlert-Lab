from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).parents[2]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def _pom() -> ET.Element:
    return ET.parse(ROOT / "pom.xml").getroot()


def test_release_version_and_reproducible_timestamp_are_fixed() -> None:
    root = _pom()
    assert root.find("m:version", NS).text == "1.0.0-rc.1"
    properties = root.find("m:properties", NS)
    assert properties.find("m:project.build.outputTimestamp", NS).text == "2026-08-17T00:00:00Z"
    assert "SNAPSHOT" not in (ROOT / "pom.xml").read_text()


def test_all_required_plugins_are_explicitly_pinned() -> None:
    plugins = {
        plugin.find("m:artifactId", NS).text: plugin.find("m:version", NS).text
        for plugin in _pom().findall("m:build/m:plugins/m:plugin", NS)
    }
    assert plugins == {
        "maven-clean-plugin": "3.4.1",
        "maven-resources-plugin": "3.3.1",
        "maven-compiler-plugin": "3.14.0",
        "maven-surefire-plugin": "3.5.3",
        "maven-jar-plugin": "3.4.2",
        "maven-enforcer-plugin": "3.5.0",
        "duplicate-finder-maven-plugin": "2.0.1",
        "maven-dependency-plugin": "3.8.1",
    }


def test_enforcer_rules_and_ranges_are_present() -> None:
    text = (ROOT / "pom.xml").read_text()
    assert "<requireMavenVersion><version>[3.8.7,3.8.8)</version>" in text
    assert "<requireJavaVersion><version>[21,22)</version>" in text
    assert "<dependencyConvergence/>" in text
    assert "<requireUpperBoundDeps/>" in text
    assert "<failBuildInCaseOfConflict>true</failBuildInCaseOfConflict>" in text


def test_isolated_repository_configuration_is_relative_and_empty() -> None:
    assert (ROOT / ".mvn/maven.config").read_text().strip() == "-Dmaven.repo.local=.mvn/repository"
    assert ".mvn/repository/" in (ROOT / ".gitignore").read_text()


def test_runtime_verifier_is_non_executing_and_fail_closed() -> None:
    wrapper = (ROOT / "bin/java-receiver").read_text()
    verify = wrapper[wrapper.index("verify_runtime()") : wrapper.index("case \"$action\"")]
    assert "verify_java" in verify
    assert "java_bin=/usr/bin/java" in wrapper
    assert '"$java_bin" -version' in wrapper
    assert "jar tf" in verify
    assert "ScenarioOpenWireReceiver.class" in verify
    assert "exec java" not in verify
    assert "/tmp|/tmp/*" in verify
    assert "backups" in verify and "Downloads" in verify
    assert "activemq-all" in verify
    assert "ActiveMQConnectionFactory.class" in verify
    assert "javax/jms/Connection.class" in verify
    assert "log4j-api-2.26.1.jar" in verify
    assert "log4j-core-2.26.1.jar" in verify
    assert "verify-runtime)" in wrapper


def test_checksum_tools_cover_only_isolated_jar_and_pom_artifacts() -> None:
    generate = (ROOT / "build-support/generate-maven-checksums").read_text()
    verify = (ROOT / "build-support/verify-maven-checksums").read_text()
    assert ".mvn/repository" in generate
    assert "-name '*.jar'" in generate and "-name '*.pom'" in generate
    assert "sha256sum" in generate
    assert "maven-artifacts.sha256" in generate and "maven-artifacts.sha256" in verify
    assert "sha256sum --check --strict" in verify
    assert "cmp --silent" in verify
