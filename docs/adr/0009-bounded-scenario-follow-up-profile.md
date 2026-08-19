# ADR 0009: Bounded Scenario follow-up profile

## Status

Accepted, installed, and validated against historical captures and one live
follow-up delivery in the final `cd8e55c` Scenario acceptance.

## Context

Two independent M4.6 Westmoreland Scenario runs produced a delayed
`event_message` with `message_type="follow_up"`, `version="900"`, and
`alg_vers="1.1.1 2019-04-17"`. Their structural signatures match. The
follow-up is a legitimate observed Scenario delivery but is not a normal Event
`new` or `update` and must not be forced into `ShakeAlertEventUpdate`.

## Decision

A hardened `ShakeAlertMessageParser` dispatches only two discriminator
combinations:

- Event: `message_type=new|update` and `alg_vers="2.3.23 2020-04-01"`;
- follow-up: `message_type=follow_up`, `version=900`, and
  `alg_vers="1.1.1 2019-04-17"`.

Every other combination fails closed. Event parsing remains delegated to the
existing strict Event parser. The separate follow-up parser produces an
immutable `ShakeAlertFollowUp` under the sealed `ShakeAlertMessage`
boundary.

The observed follow-up requires exactly one `core_info`, `contributors`,
`gm_info`, and `follow_up_info`. It accepts exactly two typed notices
(`short_review` and `wea`), four contours, the exact MMI/PGA/PGV unit
contract, and eight declared polygon vertices plus the repeated closing
coordinate. Decimal lexical length, notice/polygon text, coordinates, numeric
ranges, XML structure, namespaces, attributes, and cardinalities are bounded.

The initial four-contour/eight-vertex cardinalities deliberately support only
the twice-observed profile. Other USGS follow-up variants remain
`UNSUPPORTED_SCHEMA` pending evidence and review.

## Consequences

Native capture ordering, transport, authentication, Topic subscription,
systemd lifecycle, retry/fallback, publishing, and Production isolation do not
change. Every delivery is captured before dispatch. Follow-up notices are
application-owned immutable text and must not appear in health, rejection, or
lifecycle logs. Historical rejection records remain evidence of the parser
version active when they were created.
