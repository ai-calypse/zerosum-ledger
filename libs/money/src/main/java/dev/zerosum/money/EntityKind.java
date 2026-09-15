package dev.zerosum.money;

/** Entity kinds, parsed from the prefix of an entity ID such as {@code rider:R1} (D01-6). */
public enum EntityKind {
    RIDER("rider"),
    DRIVER("driver"),
    PLATFORM("platform"),
    PROVIDER("provider");

    private final String prefix;

    EntityKind(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}
