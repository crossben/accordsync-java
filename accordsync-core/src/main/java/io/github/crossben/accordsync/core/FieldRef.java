package io.github.crossben.accordsync.core;

/**
 * A field of a record.
 *
 * @param record the record id
 * @param field the field name
 */
public record FieldRef(String record, String field) {}
