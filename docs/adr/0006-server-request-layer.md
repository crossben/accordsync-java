# ADR-J06: the server's framework-agnostic request layer

**Status:** accepted (J4)

## Decisions

1. **`AccordServer.handle(HttpRequest) -> HttpResponse`**, plain records (method, path, raw query,
   headers lowercased, body bytes; status, ordered header pairs, body bytes). It never throws:
   4xx errors are `{"error": "..."}` like `app.ts`, anything else is logged (`System.Logger`) and
   answered `500 {"error":"internal error"}`. Every response carries `Accord-Protocol: 1`; 429
   carries `Retry-After` (whole seconds, rounded up). Framework adapters (the Spring Boot starter,
   J5; the conformance harness on `com.sun.net.httpserver`) only translate.
2. **Same routing as Hono**: `/health` (GET, HEAD), `/v1/push` (POST), `/v1/pull` (GET, HEAD);
   anything else is a JSON `404 {"error":"not found"}` (Hono's default 404 is text; the PHP and
   Python ports chose JSON too). The body limit applies to every `/v1/*` path **before** auth, from
   the body length or a larger `Content-Length`, as Hono's `bodyLimit` middleware does.
3. **Query numbers are read with JavaScript's `Number()`** (`JsNumber`): empty is 0, `0x10` is 16,
   `1e3` is 1000, JavaScript whitespace is trimmed; then `Number.isSafeInteger`. The first value of
   a repeated parameter wins; `+` decodes to a space and a malformed `%` escape is kept as is.
4. **Push bodies** are decoded as UTF-8 with replacement and a leading BOM dropped (like
   `Response.json()`), parsed with the core's parser (`JSON.parse` semantics), and must be exactly
   `{"ops": [...]}` (TypeBox `additionalProperties: false`).
5. **CORS** is written by hand, header for header like Hono's `cors()` with the origins list
   (`Vary: Origin`, preflight 204 with `Access-Control-Max-Age: 600`), as the Python port does.
6. **The definition** is built with `AccordServer.define(d -> d.schema(...).scope(type, fn)
   .access(fn).auth(...))` and validated at once with the Python port's checks (a scope function
   for every type, exactly one of JWKS/HS256, positive limits, `maxScopeDelta >= 0`, rate limits > 0).
   Claims reach `access` as the core's `JsonObject` (the token payload as sent).
7. **Rate limiting** is a `RateLimiter` interface (`take(key)`, `clear()`) with an in-memory token
   bucket (`ratelimit.ts` line for line). `resetRateLimits()` clears both buckets (the control API's
   `/reset`).
