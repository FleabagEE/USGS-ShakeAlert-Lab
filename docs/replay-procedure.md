# Local Replay Procedure

Replay reads preserved captures and sends them only to an in-process queue through `InternalQueueSink`. It preserves order, supports timing scale, filtering, pause, resume, step, and stop controls. It has no external publisher. A future loopback broker adapter must explicitly reject non-loopback destinations; MQTT 3.1.1 may be added only for the future CUBE regression boundary and cannot become an endpoint assumption. Keep replay classification separate and never use production credentials.
