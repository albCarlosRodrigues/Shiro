package dev.squaddesktop.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.squaddesktop.features.Feature;
import dev.squaddesktop.features.FeatureService;
import dev.squaddesktop.projects.Project;
import dev.squaddesktop.runs.RunOutcome;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.testsupport.Fixture;
import dev.squaddesktop.testsupport.TestJson;

class WorkspaceTransferTest {

    @TempDir
    Path dir;

    private Fixture f;
    private WorkspaceTransfer transfer;

    @BeforeEach
    void setUp() {
        f = new Fixture(dir, (spec, sink, control) -> RunOutcome.failure(1, "x", "x", List.of()));
        transfer = new WorkspaceTransfer(f.database, f.clock, dir.resolve("backups"), "teste", f.runs);
    }

    @AfterEach
    void close() {
        f.close();
    }

    @Test
    void exportaEImportaComCopiaAntesEValidacao() throws Exception {
        Project project = f.projectWithSquad("Ação & Cia");
        Feature a = f.features.create(project.id(), new FeatureService.FeatureDraft("A", "Primeira", null, null, null, null, null,
                List.of(new Feature.Criterion("ok", false)), null, null));
        f.features.create(project.id(), new FeatureService.FeatureDraft("B", "Segunda", null, null, null, null, null, null, null, List.of("A")));
        Path folder = Path.of(project.folderPath());
        Files.writeString(folder.resolve("codigo.txt"), "não tocar");

        // ida e volta pelo JSON, como a interface faz
        Object payload = TestJson.CODEC.parse(TestJson.CODEC.write(transfer.export()));
        WorkspaceTransfer.ImportCheck check = transfer.validate(payload);
        assertThat(check.ok()).isTrue();
        assertThat(check.counts()).containsEntry("projects", 1).containsEntry("features", 2);

        f.projects.delete(project.id());
        assertThat(f.projects.list(true, null)).isEmpty();

        WorkspaceTransfer.ImportResult result = transfer.importWorkspace(payload);
        assertThat(Path.of(result.backup().path())).exists();
        assertThat(result.backup().reason()).isEqualTo("before-import");
        assertThat(f.projects.get(project.id()).name()).isEqualTo("Ação & Cia");
        assertThat(f.features.list(project.id())).extracting(Feature::code).containsExactly("A", "B");
        assertThat(f.features.list(project.id()).get(1).dependsOn()).containsExactly(a.id());
        assertThat(Files.readString(folder.resolve("codigo.txt"))).isEqualTo("não tocar");
        assertThat(transfer.backups()).isNotEmpty();
    }

    @Test
    void recusaArquivoEstranhoOuDeVersaoMaisNova() {
        assertThat(transfer.validate(Map.of("format", "outro")).ok()).isFalse();
        Map<String, Object> newer = Map.of("format", WorkspaceTransfer.FORMAT, "version", 1, "schemaVersion", 999, "tables", Map.of());
        assertThat(transfer.validate(newer).problems()).anyMatch(p -> p.contains("mais nova"));
        Map<String, Object> badColumn = Map.of("format", WorkspaceTransfer.FORMAT, "version", 1, "schemaVersion", 2,
                "tables", Map.of("project", List.of(Map.of("id", "x", "coluna_falsa", 1))));
        assertThat(transfer.validate(badColumn).problems()).anyMatch(p -> p.contains("coluna_falsa"));
        assertThatThrownBy(() -> transfer.importWorkspace(badColumn)).isInstanceOf(ApiException.class);
    }
}
