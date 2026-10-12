package dev.squaddesktop.agents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.squaddesktop.shared.ApiException;
import dev.squaddesktop.squads.Squad;
import dev.squaddesktop.squads.SquadDraft;
import dev.squaddesktop.system.SampleData;
import dev.squaddesktop.testsupport.Fixture;

class AgentAndSquadTest {

    @TempDir
    Path dir;

    private Fixture fx;
    private SampleData samples;

    @BeforeEach
    void setUp() {
        fx = new Fixture(dir, null);
        samples = new SampleData(fx.database, fx.agents, fx.squads, fx.projects, fx.documents, fx.sessions);
    }

    @AfterEach
    void tearDown() {
        fx.close();
    }

    @Test
    void modelosCriamUmaSquadValida() {
        SampleData.Starter starter = samples.ensureStarterAgents();
        assertThat(starter.created()).isTrue();
        assertThat(starter.agents()).hasSize(AgentTemplates.all().size());
        assertThat(starter.squad().operatorIds()).hasSize(4);
        assertThat(samples.ensureStarterAgents().created()).as("não duplica").isFalse();
    }

    @Test
    void naoExcluiAgenteQueOcupaAssentoDeSquadAtiva() {
        Squad squad = samples.ensureStarterAgents().squad();
        String commander = squad.commanderId();
        assertThatThrownBy(() -> fx.agents.delete(commander)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).details().get("squads")).isEqualTo(List.of("Squad padrão")));
        Agent current = fx.agents.get(commander);
        AgentDraft asOperator = new AgentDraft(current.name(), AgentRole.OPERATOR, current.specialty(), current.icon(), current.color(),
                current.responsibility(), current.personality(), current.instructions(), current.guidelines(), null, null, null,
                current.tools(), current.allowedTools(), current.disallowedTools(), null);
        assertThatThrownBy(() -> fx.agents.update(commander, asOperator)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("agent_in_use");
    }

    @Test
    void agenteSemUsoEExcluidoEComHistoricoEArquivado() throws Exception {
        Agent loose = fx.operator();
        assertThat(fx.agents.delete(loose.id())).isEqualTo(AgentService.Removal.DELETED);
        Agent used = fx.operator();
        fx.session(fx.project("p"), used);
        assertThat(fx.agents.delete(used.id())).isEqualTo(AgentService.Removal.ARCHIVED);
        assertThat(fx.agents.list(false)).extracting(Agent::id).doesNotContain(used.id());
    }

    @Test
    void squadExigePapeisCorretosEAoMenosUmOperador() {
        SampleData.Starter starter = samples.ensureStarterAgents();
        Squad base = starter.squad();
        assertThatThrownBy(() -> fx.squads.create(new SquadDraft("x", "", base.commanderId(), base.requirementsId(), base.architectureId(), List.of())))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("missing_operator");
        assertThatThrownBy(() -> fx.squads.create(new SquadDraft("x", "", base.requirementsId(), base.requirementsId(), base.architectureId(), base.operatorIds())))
                .isInstanceOf(ApiException.class).extracting("code").isEqualTo("wrong_role");
        Squad copy = fx.squads.create(new SquadDraft("Reaproveitada", "", base.commanderId(), base.requirementsId(), base.architectureId(), base.operatorIds()));
        assertThat(copy.memberIds()).containsAll(base.memberIds());
    }

    @Test
    void validaCamposEFerramentas() {
        AgentDraft base = AgentTemplates.find("operator-qa").orElseThrow().draft();
        AgentDraft bypass = new AgentDraft(base.name(), base.role(), base.specialty(), base.icon(), base.color(), base.responsibility(),
                base.personality(), base.instructions(), base.guidelines(), null, null, "bypassPermissions", null, List.of(), List.of(), null);
        assertThatThrownBy(() -> fx.agents.create(bypass)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("invalid_permission_mode");
        AgentDraft badRule = new AgentDraft(base.name(), base.role(), base.specialty(), base.icon(), base.color(), base.responsibility(),
                base.personality(), base.instructions(), base.guidelines(), null, null, null, null, List.of("Bash(rm -rf /"), List.of(), null);
        assertThatThrownBy(() -> fx.agents.create(badRule)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("invalid_tool");
    }

    @Test
    void duplicaELiberaFerramenta() {
        Agent qa = fx.agents.createFromTemplate("operator-qa");
        Agent copy = fx.agents.duplicate(qa.id());
        assertThat(copy.name()).endsWith("(cópia)");
        Agent allowed = fx.agents.allowTool(copy.id(), "Bash(npm run lint *)");
        assertThat(allowed.allowedTools()).contains("Bash(npm run lint *)");
        assertThat(fx.agents.allowTool(copy.id(), "Bash(npm run lint *)").allowedTools()).filteredOn(r -> r.equals("Bash(npm run lint *)")).hasSize(1);
    }

    @Test
    void exportaMarkdownNoFormatoDeSubagente() {
        Agent architect = fx.agents.createFromTemplate("architecture");
        String markdown = AgentMarkdown.export(architect);
        assertThat(markdown).startsWith("---\nname: arquiteto\n");
        assertThat(markdown).contains("tools: Read, Glob, Grep").contains("# Você é Arquiteto");
        assertThat(AgentMarkdown.slug("Operador de Banco de Dados")).isEqualTo("operador-de-banco-de-dados");
    }

    @Test
    void projetoDeExemploFicaIdentificado() {
        var project = samples.loadSampleProject();
        assertThat(project.sample()).isTrue();
        assertThat(project.name()).contains("(exemplo)");
        assertThat(fx.sessions.listByProject(project.id(), false)).hasSize(1);
    }
}
