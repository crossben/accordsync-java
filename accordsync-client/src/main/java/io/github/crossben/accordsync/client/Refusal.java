package io.github.crossben.accordsync.client;

/**
 * A local write the server refused. It has already been rolled back on this device.
 *
 * @param opId the op id
 * @param record the record id
 * @param field the field
 * @param reason the server's reason
 */
public record Refusal(String opId, String record, String field, String reason) {}
