from pathlib import Path


SOURCE = (
    Path(__file__).parents[2] / "tools" / "ScenarioOpenWireReceiver.java"
).read_text()


def test_openwire_native_capture_is_bounded_and_committed_atomically() -> None:
    for invariant in (
        "maximum <= 0 || maximum > 16777216",
        "nativeBody.length > maximumPayloadBytes",
        "payload.length > maximumPayloadBytes",
        "bytes.getBodyLength() > maximumPayloadBytes",
        "out.size() + count > maximumPayloadBytes",
        "StandardOpenOption.CREATE_NEW",
        "channel.force(true)",
        "StandardCopyOption.ATOMIC_MOVE",
        "dir.force(true)",
    ):
        assert invariant in SOURCE


def test_callback_precedes_validation_and_commit_event_follows_capture() -> None:
    callback = SOURCE[
        SOURCE.index("consumer.setMessageListener") : SOURCE.index("connection.start()")
    ]
    assert callback.index("beforePayloadValidation") < callback.index("capture(message")
    assert callback.index("capture(message") < callback.index('lifecycle("CAPTURE_COMMITTED"')
