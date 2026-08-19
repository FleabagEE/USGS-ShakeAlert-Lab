# Native Message Field Dictionary

Authorized Scenario captures now establish the native capture envelope and two
narrow payload profiles. The envelope fields (`capture_id`, environment,
endpoint, protocol/version, destination, receive/server timestamps,
allowlisted identifiers, redelivery, content type, size, hash, and native
payload) are confirmed laboratory metadata. Transport-native objects do not
cross into the immutable application envelope.

The parser supports only observed, bounded structures:

- Event messages with `message_type=new|update`, the observed Event algorithm
  discriminator, numeric update version, `core_info`, `contributors`, optional
  `gm_info`, and the explicitly bounded optional finite-fault hierarchy.
- Follow-up messages with the observed `follow_up`, version-900 discriminator,
  typed notices, and bounded ground-motion contours/polygons.

These profiles confirm that the listed structures occurred in authorized
Scenario evidence; they do not make undocumented semantic claims. Cancellation,
heartbeat, complete sequence/version meaning, quality/station semantics,
broader rupture/contour variants, expected rates, and authoritative maximum
sizes remain **UNKNOWN** or unsupported pending USGS evidence. Broker
acknowledgement confirmation and redelivery-window semantics also remain open
even though the application ACK boundary is implemented.
