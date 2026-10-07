# ADR-J03: the core parses and prints JSON itself

**Status:** accepted (implemented in J1)

## Decision

`accordsync-core` has its own small JSON parser and canonical encoder, and no dependency.
Values are held in a model that keeps what JavaScript sees: objects vs arrays, keys as strings
(including `"10"`), numbers as JavaScript doubles (with integers kept exact within ±2^53), and lone
surrogates in strings.

## Why

Canonical JSON must equal `JSON.stringify` byte for byte: array-index keys first, numbers printed
as ECMA-262 does (`1e+21`, `1`, `0` for `-0`, `1e-7`), lone surrogates escaped. `Double.toString`
and general-purpose JSON libraries differ on each of these, and their boxed number types make `1`
and `1.0` unequal. Owning the value model at the one place data enters the core removes the whole
class of bugs, as it did for the PHP port (ADR-P05). No dependency also keeps the core Android-safe.
