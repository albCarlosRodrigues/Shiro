package dev.squaddesktop.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class PermissionRulesTest {

    @Test
    void sugereRegraEstreitaParaComandos() {
        assertThat(PermissionRules.suggest("Bash", Map.of("command", "npm run build"))).isEqualTo("Bash(npm run *)");
        assertThat(PermissionRules.suggest("Bash", Map.of("command", "git status --short"))).isEqualTo("Bash(git status *)");
        assertThat(PermissionRules.suggest("Bash", Map.of("command", "ls -la"))).isEqualTo("Bash(ls *)");
        assertThat(PermissionRules.suggest("Bash", Map.of("command", "mvn -q verify"))).isEqualTo("Bash(mvn *)");
        assertThat(PermissionRules.suggest("Bash", Map.of("command", "python3 -c \"print(40+2)\""))).isEqualTo("Bash(python3 *)");
        assertThat(PermissionRules.suggest("Bash", Map.of("command", "node -e 'console.log(1)'"))).isEqualTo("Bash(node *)");
    }

    @Test
    void naoSugereParaComandosCompostos() {
        assertThat(PermissionRules.suggest("Bash", Map.of("command", "npm test && rm -rf build"))).isNull();
        assertThat(PermissionRules.suggest("Bash", Map.of("command", "cat a | grep b"))).isNull();
        assertThat(PermissionRules.suggest("Bash", Map.of("command", "echo $(whoami)"))).isNull();
        assertThat(PermissionRules.suggest("Bash", Map.of("command", "echo \"$(whoami)\""))).isNull();
        assertThat(PermissionRules.suggest("Bash", Map.of("command", "echo 'aberto"))).isNull();
        assertThat(PermissionRules.suggest("Bash", Map.of("command", "python3 -c 'print(1)' > saida.txt"))).isNull();
        assertThat(PermissionRules.suggest("Bash", Map.of())).isNull();
    }

    @Test
    void ferramentasDeArquivoUsamONome() {
        assertThat(PermissionRules.suggest("Write", Map.of("file_path", "a.txt"))).isEqualTo("Write");
        assertThat(PermissionRules.suggest("nome inválido", Map.of())).isNull();
    }
}
