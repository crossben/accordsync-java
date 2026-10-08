package io.github.crossben.accordsync.server;

import io.github.crossben.accordsync.core.Json;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * {@code java -cp ... io.github.crossben.accordsync.server.Cli migrate|compact}.
 *
 * <p>{@code migrate} needs only {@code ACCORD_DATABASE_URL} (or {@code --database-url}).
 * {@code compact} also needs the server definition: {@code --definition <class>} (or
 * {@code ACCORD_SERVER}) naming a class with a public no-argument constructor that implements
 * {@code Supplier<ServerDefinition>}.
 */
public final class Cli {
    private Cli() {}

    /**
     * Runs the command.
     *
     * @param args {@code migrate|compact [--database-url URL] [--definition CLASS]}
     * @throws Exception on failure
     */
    public static void main(String[] args) throws Exception {
        System.exit(run(args));
    }

    static int run(String[] args) throws Exception {
        String command = null;
        String url = System.getenv("ACCORD_DATABASE_URL");
        String definition = System.getenv("ACCORD_SERVER");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--database-url" -> url = args[++i];
                case "--definition" -> definition = args[++i];
                default -> command = args[i];
            }
        }
        if (!"migrate".equals(command) && !"compact".equals(command)) {
            System.err.println("usage: Cli migrate|compact [--database-url URL] [--definition CLASS]");
            return 2;
        }
        if (url == null || url.isEmpty()) {
            System.err.println("ACCORD_DATABASE_URL (or --database-url) is required");
            return 2;
        }
        DataSource db = Databases.fromUrl(url);
        if (command.equals("migrate")) {
            List<String> ran = Migrations.migrate(db);
            System.out.println(ran.isEmpty() ? "up to date" : "applied " + ran.size() + " migration(s): " + String.join(", ", ran));
            return 0;
        }
        if (definition == null || definition.isEmpty()) {
            System.err.println("compact needs --definition CLASS (or ACCORD_SERVER)");
            return 2;
        }
        Object supplier = Class.forName(definition).getConstructor().newInstance();
        if (!(supplier instanceof Supplier<?> s) || !(s.get() instanceof ServerDefinition def)) {
            System.err.println(definition + " is not a Supplier<ServerDefinition>");
            return 2;
        }
        System.out.println(Json.stringify(new AccordServer(def, db).compact()));
        return 0;
    }
}
