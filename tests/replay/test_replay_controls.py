from datetime import datetime,timezone
from queue import Queue
from shakealert_lab.messaging.inbound import Environment,MessageEnvelope
from shakealert_lab.replay import InternalQueueSink,ReplayController,replay
from shakealert_lab.storage.capture import NativeCapture
def capture()->NativeCapture:return NativeCapture.create(MessageEnvelope(payload=b"x",received_at_utc=datetime.now(timezone.utc),environment=Environment.SCENARIO,connection_name="replay"))
def test_paused_replay_allows_one_step()->None:
    controller=ReplayController();controller.pause();controller.step();queue=Queue();assert replay([capture()],InternalQueueSink(queue),controller=controller)==1
def test_stopped_replay_sends_nothing()->None:
    controller=ReplayController();controller.stop();queue=Queue();assert replay([capture()],InternalQueueSink(queue),controller=controller)==0 and queue.empty()
