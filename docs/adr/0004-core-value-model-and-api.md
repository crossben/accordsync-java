# ADR-J04: the core's value model and Java API shape

**Status:** accepted (J1)

## Decisions

1. **Numbers.** `JsonNumber` holds a `Long` for whole numbers within ±(2^53 - 1) and a `Double`
   otherwise (`JsonNumber.of(long)` beyond that range becomes the nearest double, as in
   JavaScript). The parser keeps the written form's kind (`1` -> Long, `1.0`/`1e0` -> Double,
   `-0` -> Double -0.0, integers beyond 2^53 -> Double). Equality and hashing are numeric
   (`1 == 1.0`, `0 == -0`), so sets dedupe like JavaScript's SameValueZero / `===`. Printing a
   Double uses ECMA-262 Number::toString with shortest round-trip digits computed via `BigDecimal`
   (FLOOR/CEILING candidates per precision, closest wins), not `Double.toString`: JDK 17's
   `Double.toString` is not always shortest (fixed only in JDK 19).
2. **Counters** accumulate in a `double`, like the TypeScript core: totals never wrap, and they
   read as a `Long` when whole and safe.
3. **Never-written fields.** `FieldState.read()` returns `Optional.empty()` for a never-written lww
   or conflict field (the PHP port's `Absent` marker); `Replica.read` leaves those keys out, as
   `canonicalJson` drops `undefined`. `Replica.read` returns `Optional<JsonObject>` (empty for an
   untouched record).
4. **Field snapshots are their JSON.** `RecordSnapshot` holds each field's snapshot as the
   `JsonObject` that travels (`{"strategy":"lww","winner":...}` etc.); `fromJson` validates it.
5. **Record ids** are checked by hand (type regex + id length 1..256 UTF-16 code units), because
   Java's regex `.` counts code points. All other regexes use `Matcher.matches()` (Java's `$`
   would accept a trailing newline).
6. **Errors** are `AccordException` (unchecked); `ClockSkewException` extends it.
7. **Op sequence numbers** are `long`; a 16-digit sequence above 2^53 is rounded as `Number()`
   would round it.
