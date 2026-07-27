# Normalization Model

`NormalizedMessage` contains native `capture_id` provenance, UTC receipt time, evidence-based environment, generic disposition, optional verified message type, and an immutable mapping of verified fields. The framework does not extract or fabricate event, countdown, intensity, PGA, PGV, or site-specific values. A future verified parser supplies fields and units; empty input yields an empty normalized field mapping.
