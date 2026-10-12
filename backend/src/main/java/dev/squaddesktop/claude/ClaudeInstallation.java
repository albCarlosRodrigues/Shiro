package dev.squaddesktop.claude;

import java.util.List;

/**
 * Onde está o Claude Code e como iniciá-lo.
 *
 * @param executable caminho encontrado (o que a pessoa vê e configura)
 * @param launcher   prefixo real da linha de comando; difere de {@code executable} quando o achado é um atalho
 *                   do npm ({@code claude.cmd}), caso em que chamamos o Node diretamente para não passar por cmd.exe
 * @param source     de onde veio: "configurado", "PATH" ou "local conhecido"
 * @param problem    preenchido quando o executável existe mas não pode ser iniciado com segurança
 */
public record ClaudeInstallation(String executable, List<String> launcher, String source, String problem) {

    public boolean usable() {
        return problem == null && !launcher.isEmpty();
    }
}
