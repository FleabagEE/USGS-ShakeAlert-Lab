"""Certificate renewal threshold evaluation from verified metadata."""
from datetime import datetime,timedelta,timezone
def certificate_renewal_due(*,not_after_utc:datetime,now_utc:datetime|None=None,warning_window:timedelta=timedelta(days=30))->bool:
    if not_after_utc.tzinfo is None or not_after_utc.utcoffset() is None:raise ValueError("certificate expiry must be timezone-aware")
    now=datetime.now(timezone.utc) if now_utc is None else now_utc
    return not_after_utc<=now+warning_window
