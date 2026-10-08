package io.github.crossben.accordsync.core;

/**
 * A JSON value as JavaScript sees it: objects with string keys in insertion order, arrays,
 * strings (UTF-16, lone surrogates allowed), numbers with JavaScript double semantics, booleans and
 * null. Values are immutable; {@code equals} is JSON value equality ({@code 1} equals {@code 1.0}).
 */
public sealed interface JsonValue permits JsonObject, JsonArray, JsonString, JsonNumber, JsonBool, JsonNull {}
