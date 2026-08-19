import java.util.Objects;

/** One validated, typed human-readable notice from a supported follow-up message. */
record FollowUpNotice(Type type, String text) {
    enum Type { SHORT_REVIEW, WEA }

    FollowUpNotice {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
    }
}
