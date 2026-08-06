# Scenario Server Procedure

Prerequisites: signed authorization, verified endpoint matrix, protected
scenario credentials, synchronized clock, approved non-wildcard destination,
TLS validation, available disk, and `connect_authorized=true` after peer
review. Start only the scenario unit. Record connection health, request an
authorized scenario, preserve every native record, and observe initial/update/
final or cancellation, heartbeat, duplicates, redelivery, ordering, and
concurrent events. Stop and quarantine on ambiguous environment evidence.

## Current integration checkpoint

DNS, TCP, TLS certificate and hostname validation, and ActiveMQ OpenWire
negotiation to `scenario.eew.shakealert.org:61617` have succeeded.
Authentication is reached but currently fails with a sanitized broker response
indicating that the username or password is invalid. No subscription to
`eew.test_QuakeLogic-SA1.dm.data` has been created, no Scenario has been
scheduled, and no message has been received. Do not retry until USGS confirms
the correct broker credentials and Scenario account authorization.
