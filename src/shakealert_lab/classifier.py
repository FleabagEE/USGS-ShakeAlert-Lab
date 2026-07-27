"""Evidence-based environment classification with conflict quarantine."""
from dataclasses import dataclass
from enum import Enum
class MessageEnvironment(Enum):
    LIVE="LIVE";TEST="TEST";EXERCISE="EXERCISE";SCENARIO="SCENARIO";UNKNOWN="UNKNOWN"
@dataclass(frozen=True,slots=True)
class ClassificationEvidence:source:str;classification:MessageEnvironment
def classify_environment(evidence:tuple[ClassificationEvidence,...])->MessageEnvironment:
    claims={x.classification for x in evidence if x.classification is not MessageEnvironment.UNKNOWN}
    return next(iter(claims)) if len(claims)==1 else MessageEnvironment.UNKNOWN
