package io.github.crossben.accordsync.core;

import java.util.List;

/** How a field merges. */
public enum Strategy {
    /** Highest clock wins. For names, notes and simple scalars. */
    LWW("lww", List.of("assign")),
    /** Sum of all increments; none is ever lost. */
    COUNTER("counter", List.of("inc")),
    /** Add-wins set of strings or numbers. */
    SET("set", List.of("add", "remove")),
    /** Concurrent values are all kept and the field is flagged; the app resolves it. */
    CONFLICT("conflict", List.of("assign"));

    private final String id;
    private final List<String> kinds;

    Strategy(String id, List<String> kinds) {
        this.id = id;
        this.kinds = kinds;
    }

    /**
     * The name used in schemas and snapshots.
     *
     * @return {@code lww}, {@code counter}, {@code set} or {@code conflict}
     */
    public String id() {
        return id;
    }

    /**
     * The op kinds that apply to fields of this strategy.
     *
     * @return the kinds
     */
    public List<String> kinds() {
        return kinds;
    }

    /**
     * The strategy with this name.
     *
     * @param name {@code lww}, {@code counter}, {@code set} or {@code conflict}
     * @return the strategy
     * @throws AccordException for an unknown name
     */
    public static Strategy of(String name) {
        for (Strategy s : values()) if (s.id.equals(name)) return s;
        throw new AccordException("unknown strategy \"" + name + "\"");
    }

    /** @return {@link #LWW} */
    public static Strategy lww() {
        return LWW;
    }

    /** @return {@link #COUNTER} */
    public static Strategy counter() {
        return COUNTER;
    }

    /** @return {@link #SET} */
    public static Strategy set() {
        return SET;
    }

    /** @return {@link #CONFLICT} */
    public static Strategy conflict() {
        return CONFLICT;
    }
}
