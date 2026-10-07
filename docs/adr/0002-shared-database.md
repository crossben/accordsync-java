# ADR-J02: share the TypeScript server's database, ledger and migration lock

**Status:** accepted

## Decision

The Java server uses the exact PostgreSQL schema of `@accordsync/server` (migrations 0001 to 0007),
records them in Kysely's `kysely_migration` ledger under the same names, and takes Kysely's session
advisory lock (`pg_advisory_lock(3853314791062309107)`) while migrating, like the PHP and Python
servers (their ADR-P02 and ADR-Y06). A database created by any Accord server is recognised and
upgraded by the others; new migrations are written in the Accord repository first.

## Why

Clients must not care which server they talk to, and the mixed-server fleet runs the TypeScript and
Java servers on one database at the same time.
