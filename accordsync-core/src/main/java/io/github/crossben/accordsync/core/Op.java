package io.github.crossben.accordsync.core;

/** An operation on one field of one record. Unique by {@link #opId()}: applying it twice is a no-op. */
public sealed interface Op permits AssignOp, IncOp, AddOp, RemoveOp {
    /**
     * {@code deviceId:sequence}.
     *
     * @return the op id
     */
    String opId();

    /**
     * {@code type:id}, e.g. {@code dossier:91}.
     *
     * @return the record id
     */
    String record();

    /**
     * The field written.
     *
     * @return the field name
     */
    String field();

    /**
     * The op's clock; its node is the op id's device.
     *
     * @return the clock
     */
    Hlc hlc();

    /**
     * {@code assign}, {@code inc}, {@code add} or {@code remove}.
     *
     * @return the kind
     */
    String kind();
}
