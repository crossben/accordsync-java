# ADR-J08: token verification with Nimbus JOSE + JWT

**Status:** accepted (J4)

## Decisions

Tokens are verified with Nimbus (`DefaultJWTProcessor`), configured to behave like `jose` in the
TypeScript server:

- HS256 only with a shared secret; with a JWKS, RS256/384/512, PS256/384/512 and ES256/384/512.
  **EdDSA is left out**: Nimbus needs Google Tink for Ed25519, an optional dependency this module
  does not add. An app that signs with EdDSA can be supported later with Tink on the classpath.
- JWKS keys come from `JWKSourceBuilder` with a cache (`Auth.jwksCacheSeconds`, default 300 s;
  refresh on an unknown `kid` is Nimbus's).
- `exp` and `nbf` are checked when present with **no clock tolerance** (jose's default; Nimbus
  defaults to 60 s); `iss` and `aud` only when configured; any `typ` header is accepted (Nimbus
  accepts only `JWT` or none by default).
- `sub` must be a non-empty string (`token has no "sub" claim`), anything else is
  `401 invalid token: <reason>`. Reasons are Nimbus's wording, not jose's; clients only see 401.
