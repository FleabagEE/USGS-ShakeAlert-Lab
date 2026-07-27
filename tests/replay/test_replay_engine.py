from datetime import datetime,timedelta,timezone
from queue import Queue
from shakealert_lab.messaging.inbound import Environment,MessageEnvelope
from shakealert_lab.replay import InternalQueueSink,ReplayOptions,replay
from shakealert_lab.storage.capture import NativeCapture
def test_replay_preserves_order_scales_timing_and_filters()->None:
    start=datetime(2026,1,1,tzinfo=timezone.utc)
    captures=[NativeCapture.create(MessageEnvelope(payload=str(i).encode(),received_at_utc=start+timedelta(seconds=i),environment=Environment.SCENARIO,connection_name="replay")) for i in range(3)]
    queue=Queue();delays=[];count=replay(captures,InternalQueueSink(queue),options=ReplayOptions(speed=2),predicate=lambda c:c.envelope.payload!=b"1",sleeper=delays.append)
    assert count==2 and [queue.get().envelope.payload for _ in range(2)]==[b"0",b"2"] and delays==[1.0]
