import pytest
from shakealert_lab.observability import create_server
from shakealert_lab.reliability import BackoffPolicy
def test_dashboard_refuses_non_loopback()->None:
    with pytest.raises(ValueError):create_server("0.0.0.0",0,lambda:{})
@pytest.mark.parametrize("attempt",[-1,True])
def test_backoff_rejects_invalid_attempt(attempt:object)->None:
    with pytest.raises(ValueError):BackoffPolicy().delay(attempt) # type: ignore[arg-type]
