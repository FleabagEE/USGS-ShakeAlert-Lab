from pathlib import Path


SOURCE = (
    Path(__file__).parents[2] / "tools" / "ScenarioOpenWireReceiver.java"
).read_text()
SERVICE_SOURCE = (
    Path(__file__).parents[2] / "tools" / "ScenarioReceiverService.java"
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
    callback = SOURCE[SOURCE.index("(message, generation) -> {") : SOURCE.index("instanceLock);")]
    assert callback.index("beforePayloadValidation") < callback.index("NativeCaptureCommit committed = capture(")
    assert callback.index("NativeCaptureCommit committed = capture(") < callback.index('lifecycle("CAPTURE_COMMITTED"')
    listener = SERVICE_SOURCE[
        SERVICE_SOURCE.index("createdConsumer.setMessageListener") :
        SERVICE_SOURCE.index("createdConnection.start()")
    ]
    assert "acceptCallback(activationGeneration, message)" in listener
