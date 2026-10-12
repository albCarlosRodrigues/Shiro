package dev.squaddesktop.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.squaddesktop.shared.Texts;

/**
 * Pastas do aplicativo. Ficam separadas das pastas dos projetos: o banco, os backups e os arquivos
 * temporários do Squad Desktop nunca são gravados onde os agentes trabalham.
 */
public record SquadPaths(Path dataDir, Path database, Path backups, Path temp, Path scratch, Path logs) {

    public static SquadPaths resolve(String configured) {
        Path base = Texts.isBlank(configured) ? defaultDataDir() : Path.of(configured.strip());
        base = base.toAbsolutePath().normalize();
        return new SquadPaths(base, base.resolve("squad.db"), base.resolve("backups"), base.resolve("tmp"),
                base.resolve("workspaces"), base.resolve("logs"));
    }

    public void create() {
        try {
            for (Path dir : new Path[] {dataDir, backups, temp, scratch, logs}) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível criar a pasta de dados " + dataDir + ": " + e.getMessage(), e);
        }
    }

    /** Pasta de dados padrão por sistema; o shell desktop normalmente informa a sua via SQUAD_DATA_DIR. */
    static Path defaultDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        Path home = Path.of(System.getProperty("user.home", "."));
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return (appData == null ? home.resolve("AppData").resolve("Roaming") : Path.of(appData)).resolve("Squad Desktop").resolve("data");
        }
        if (os.contains("mac")) {
            return home.resolve("Library").resolve("Application Support").resolve("Squad Desktop").resolve("data");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return (xdg == null ? home.resolve(".local").resolve("share") : Path.of(xdg)).resolve("squad-desktop");
    }
}
