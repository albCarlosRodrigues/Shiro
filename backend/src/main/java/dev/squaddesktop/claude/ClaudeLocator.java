package dev.squaddesktop.claude;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Descobre o executável do Claude Code: caminho configurado, PATH e locais usuais de instalação. */
public final class ClaudeLocator {

    private final Map<String, String> env;
    private final Path home;
    private final boolean windows;

    public ClaudeLocator() {
        this(System.getenv(), Path.of(System.getProperty("user.home", ".")), isWindows());
    }

    public ClaudeLocator(Map<String, String> env, Path home, boolean windows) {
        this.env = env;
        this.home = home;
        this.windows = windows;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * @param configuredPath caminho definido nas preferências; quando presente é o único considerado,
     *                       para que um caminho errado apareça como erro em vez de ser trocado em silêncio
     */
    public Optional<ClaudeInstallation> locate(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path path = toPath(configuredPath.strip());
            if (path == null || !Files.isRegularFile(path)) {
                return Optional.of(new ClaudeInstallation(configuredPath.strip(), List.of(), "configurado",
                        "O caminho configurado não aponta para um arquivo existente."));
            }
            return Optional.of(resolve(path, "configurado"));
        }
        for (Path dir : pathDirs()) {
            for (String name : names()) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(resolve(candidate, "PATH"));
                }
            }
        }
        for (Path dir : knownDirs()) {
            for (String name : names()) {
                Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(resolve(candidate, "local conhecido"));
                }
            }
        }
        return Optional.empty();
    }

    /** Converte o caminho encontrado na linha de comando real. */
    ClaudeInstallation resolve(Path found, String source) {
        String lower = found.getFileName().toString().toLowerCase();
        if (lower.endsWith(".cmd") || lower.endsWith(".bat") || lower.endsWith(".ps1")) {
            // Atalho do npm: executa "node …/cli.js". Chamar o .cmd exigiria cmd.exe, que reinterpreta os argumentos.
            Path cli = found.resolveSibling("node_modules").resolve("@anthropic-ai").resolve("claude-code").resolve("cli.js");
            Optional<Path> node = findNode(found.getParent());
            if (Files.isRegularFile(cli) && node.isPresent()) {
                return new ClaudeInstallation(found.toString(), List.of(node.get().toString(), cli.toString()), source, null);
            }
            return new ClaudeInstallation(found.toString(), List.of(), source,
                    "O Claude Code encontrado é um atalho de script que não pode ser iniciado com segurança. "
                            + "Instale o Claude Code pelo instalador oficial ou informe o caminho do claude.exe nas preferências.");
        }
        if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs")) {
            Optional<Path> node = findNode(found.getParent());
            if (node.isEmpty()) {
                return new ClaudeInstallation(found.toString(), List.of(), source, "Node.js não foi encontrado para executar o Claude Code.");
            }
            return new ClaudeInstallation(found.toString(), List.of(node.get().toString(), found.toString()), source, null);
        }
        return new ClaudeInstallation(found.toString(), List.of(found.toString()), source, null);
    }

    private Optional<Path> findNode(Path besides) {
        String name = windows ? "node.exe" : "node";
        if (besides != null && Files.isRegularFile(besides.resolve(name))) {
            return Optional.of(besides.resolve(name));
        }
        for (Path dir : pathDirs()) {
            if (Files.isRegularFile(dir.resolve(name))) {
                return Optional.of(dir.resolve(name));
            }
        }
        return Optional.empty();
    }

    private List<String> names() {
        return windows ? List.of("claude.exe", "claude.cmd", "claude.bat", "claude") : List.of("claude");
    }

    private List<Path> pathDirs() {
        List<Path> dirs = new ArrayList<>();
        String raw = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
        for (String part : raw.split(File.pathSeparator)) {
            Path dir = toPath(part.strip().replace("\"", ""));
            if (dir != null && !part.isBlank()) {
                dirs.add(dir);
            }
        }
        return dirs;
    }

    private List<Path> knownDirs() {
        List<Path> dirs = new ArrayList<>();
        dirs.add(home.resolve(".local").resolve("bin"));
        dirs.add(home.resolve(".claude").resolve("local"));
        if (windows) {
            addIfSet(dirs, env.get("LOCALAPPDATA"), "Microsoft", "WinGet", "Links");
            addIfSet(dirs, env.get("APPDATA"), "npm");
        } else {
            dirs.add(Path.of("/usr/local/bin"));
            dirs.add(Path.of("/opt/homebrew/bin"));
            dirs.add(home.resolve(".npm-global").resolve("bin"));
        }
        return dirs;
    }

    private static void addIfSet(List<Path> dirs, String base, String... more) {
        Path root = base == null ? null : toPath(base);
        if (root != null) {
            Path dir = root;
            for (String part : more) {
                dir = dir.resolve(part);
            }
            dirs.add(dir);
        }
    }

    private static Path toPath(String text) {
        try {
            return text == null || text.isEmpty() ? null : Path.of(text);
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
