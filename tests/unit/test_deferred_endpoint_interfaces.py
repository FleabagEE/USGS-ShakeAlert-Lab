from datetime import datetime,timedelta,timezone
import pytest
from shakealert_lab.conversion import UnitConversionRegistry,UnknownConversionError
from shakealert_lab.delivery import AcknowledgmentDecision,DeferredAcknowledger,UnverifiedAcknowledgment
from shakealert_lab.security.certificates import certificate_renewal_due
def test_acknowledgment_fails_until_verified()->None:
    with pytest.raises(UnverifiedAcknowledgment):DeferredAcknowledger().apply(None,AcknowledgmentDecision.DEFER) # type: ignore[arg-type]
def test_unit_conversion_has_no_default()->None:
    with pytest.raises(UnknownConversionError):UnitConversionRegistry().convert(1,"unknown","unknown")
def test_certificate_renewal_threshold()->None:
    now=datetime(2026,1,1,tzinfo=timezone.utc);assert certificate_renewal_due(not_after_utc=now+timedelta(days=10),now_utc=now)
    assert not certificate_renewal_due(not_after_utc=now+timedelta(days=40),now_utc=now)
