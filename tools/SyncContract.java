import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Refreshes contract/ (golden vectors, protocol schemas, conformance profile) from the Accord
 * repository.
 *
 * <pre>
 *   java tools/SyncContract.java            copy from ../app (ACCORD_APP_DIR to override)
 *   java tools/SyncContract.java --check    fail if contract/ differs from the repository (CI)
 * </pre>
 *
 * <p>The contract is committed, so this repository builds and tests alone. A new vector or
 * conformance test in the Accord repository must pass here before the next Java release.
 */
public final class SyncContract {
    private static final List<String> PARTS = List.of("vectors", "protocol/v1", "conformance");

    public static void main(String[] args) throws IOException {
        boolean check = Arrays.asList(args).contains("--check");
        Path root = Path.of("").toAbsolutePath();
        String env = System.getenv("ACCORD_APP_DIR");
        Path app = env != null ? Path.of(env) : root.resolve("../app").normalize();
        if (!Files.isRegularFile(app.resolve("vectors/lww.json"))) {
            System.err.println("SyncContract: no Accord repository at " + app + ". Set ACCORD_APP_DIR.");
            System.exit(1);
        }

        List<String> problems = new ArrayList<>();
        for (String part : PARTS) {
            Map<String, byte[]> source = jsonFiles(app.resolve(part));
            Map<String, byte[]> current = jsonFiles(root.resolve("contract").resolve(part));
            if (part.equals("conformance")) {
                // Only the profile: the rest of conformance/ is the TypeScript suite itself.
                source.keySet().retainAll(List.of("profile.json"));
            }
            TreeSet<String> names = new TreeSet<>(source.keySet());
            names.addAll(current.keySet());
            for (String name : names) {
                if (Arrays.equals(source.get(name), current.get(name))) {
                    continue;
                }
                problems.add(part + "/" + name);
                if (check) {
                    continue;
                }
                Path target = root.resolve("contract").resolve(part).resolve(name);
                if (!source.containsKey(name)) {
                    Files.delete(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.write(target, source.get(name));
                }
            }
        }

        if (check) {
            if (!problems.isEmpty()) {
                System.err.println("contract/ is behind the Accord repository:");
                problems.forEach(p -> System.err.println("  " + p));
                System.err.println("Run java tools/SyncContract.java, make the tests pass, and commit.");
                System.exit(1);
            }
            System.out.println("contract/ matches the Accord repository.");
            return;
        }
        String commit = "";
        try {
            Process git = new ProcessBuilder("git", "-C", app.toString(), "rev-parse", "HEAD").start();
            commit = new String(git.getInputStream().readAllBytes()).trim();
        } catch (IOException e) {
            // Not a git checkout: record it as unknown.
        }
        Files.writeString(root.resolve("contract/SOURCE"),
                "crossben/accordsync " + (commit.isEmpty() ? "(commit unknown)" : commit) + "\n");
        System.out.println(problems.isEmpty() ? "contract/ already up to date." : "Updated " + problems.size() + " file(s).");
    }

    /** Every .json file under {@code dir}, by path relative to it. */
    private static Map<String, byte[]> jsonFiles(Path dir) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(Files::isRegularFile).filter(f -> f.toString().endsWith(".json")).toList()) {
                out.put(dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return out;
    }

    private SyncContract() {}
}
