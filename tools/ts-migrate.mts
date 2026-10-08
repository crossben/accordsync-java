/**
 * Runs the TypeScript server's migrator (app/packages/server/src/migrate.ts) against a database,
 * for the Java ledger interop tests (LedgerInteropTest). Run from ACCORD_APP_DIR/packages/server:
 *
 *   node --import tsx --conditions=@accordsync/source <this file> <database url> [target migration]
 */
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';

const [url, target] = process.argv.slice(2);
if (!url) throw new Error('usage: ts-migrate.mts <database url> [target]');
const src = join(process.cwd(), 'src');
const { createDb } = await import(pathToFileURL(join(src, 'db.ts')).href);
const { migrateTo } = await import(pathToFileURL(join(src, 'migrate.ts')).href);
const db = createDb(url, 2);
try {
  await migrateTo(db, target || undefined);
} finally {
  await db.destroy();
}
