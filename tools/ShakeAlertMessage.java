/** Application-owned result of one supported, captured ShakeAlert message profile. */
sealed interface ShakeAlertMessage permits ShakeAlertEventUpdate, ShakeAlertFollowUp {
    ShakeAlertEventUpdate.Provenance provenance();
    String messageIdentity();
}
