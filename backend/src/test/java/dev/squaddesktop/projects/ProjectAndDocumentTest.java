package dev.squaddesktop.projects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.squaddesktop.documents.Author;
import dev.squaddesktop.documents.Document;
import dev.squaddesktop.documents.DocumentKind;
import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.testsupport.Fixture;

class ProjectAndDocumentTest {

    @TempDir
    Path dir;

    private Fixture fx;

    @BeforeEach
    void setUp() {
        fx = new Fixture(dir, null);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void criaProjetoComDocumentosFixos() throws Exception {
        Project project = fx.project("Loja Ágil");
        assertThat(fx.documents.list(project.id())).extracting(Document::kind)
                .containsExactly(DocumentKind.BRIEFING, DocumentKind.VISION, DocumentKind.SCOPE_IN, DocumentKind.SCOPE_OUT,
                        DocumentKind.GLOSSARY, DocumentKind.ARCHITECTURE);
        assertThat(project.color()).startsWith("#");
    }

    @Test
    void pastaPrecisaSerAbsolutaENaoPodeSerAPastaDeDados() {
        assertThatThrownBy(() -> fx.projects.create(new ProjectDraft("a", null, "relativa/pasta", null, null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("invalid_folder");
        String dataDir = dir.resolve("dados").resolve("sub").toString();
        assertThatThrownBy(() -> fx.projects.create(new ProjectDraft("a", null, dataDir, null, null)))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("folder_is_app_data");
    }

    @Test
    void excluirCadastroPreservaAPastaDeCodigo() throws Exception {
        Project project = fx.project("preservar");
        Path folder = Path.of(project.folderPath());
        Files.writeString(folder.resolve("main.java"), "class Main {}");
        fx.projects.delete(project.id());
        assertThat(folder.resolve("main.java")).exists();
        assertThatThrownBy(() -> fx.projects.get(project.id())).isInstanceOf(ApiException.class);
    }

    @Test
    void pesquisaEArquiva() throws Exception {
        Project a = fx.project("Catálogo de livros");
        fx.project("Financeiro");
        assertThat(fx.projects.list(false, "catá")).extracting(Project::id).containsExactly(a.id());
        fx.projects.setArchived(a.id(), true);
        assertThat(fx.projects.list(false, null)).extracting(Project::name).containsExactly("Financeiro");
        assertThat(fx.projects.list(true, null)).hasSize(2);
    }

    @Test
    void documentoVersionadoRecusaEdicaoConcorrente() throws Exception {
        Project project = fx.project("docs");
        Document briefing = fx.documents.findSingleton(project.id(), DocumentKind.BRIEFING).orElseThrow();
        Document v2 = fx.documents.save(briefing.id(), null, "Primeira versão", briefing.version(), Author.USER, null);
        assertThat(v2.version()).isEqualTo(2);

        // outra janela ainda está na versão 1
        assertThatThrownBy(() -> fx.documents.save(briefing.id(), null, "Edição antiga", 1, Author.USER, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.code()).isEqualTo("document_conflict");
                    assertThat(api.details()).containsEntry("currentContent", "Primeira versão");
                });

        Document v3 = fx.documents.save(briefing.id(), null, "Proposta do agente", 2, Author.agent("agente-1"), "proposta aplicada");
        assertThat(v3.updatedByType()).isEqualTo(Author.Type.AGENT);
        assertThat(fx.documents.versions(briefing.id())).extracting(v -> v.version()).containsExactly(2, 1);

        Document restored = fx.documents.restore(briefing.id(), 2, 3);
        assertThat(restored.content()).isEqualTo("Primeira versão");
        assertThat(restored.version()).isEqualTo(4);
    }

    @Test
    void adrsPodemSerCriadasEExcluidasMasDocumentosFixosNao() throws Exception {
        Project project = fx.project("adr");
        Document adr = fx.documents.create(project.id(), DocumentKind.ADR, "ADR 001", "Decisão", Author.USER);
        assertThat(adr.version()).isEqualTo(1);
        fx.documents.delete(adr.id());
        Document vision = fx.documents.findSingleton(project.id(), DocumentKind.VISION).orElseThrow();
        assertThatThrownBy(() -> fx.documents.delete(vision.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> fx.documents.create(project.id(), DocumentKind.VISION, "x", "", Author.USER)).isInstanceOf(ApiException.class);
    }
}
