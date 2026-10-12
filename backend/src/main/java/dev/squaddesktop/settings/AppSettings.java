package dev.squaddesktop.settings;

/**
 * Preferências do aplicativo.
 *
 * @param claudePath        caminho do executável do Claude Code; null deixa a detecção automática decidir
 * @param defaultModel      modelo usado quando o agente não define o seu; null usa o padrão do Claude Code
 * @param maxConcurrentRuns execuções simultâneas (as que escrevem na mesma pasta nunca se sobrepõem)
 * @param runTimeoutMinutes tempo máximo de uma execução antes de ser encerrada
 * @param maxBudgetUsd      teto por execução repassado ao Claude Code quando ele oferece suporte; null não limita
 * @param demoMode          usa o executor de demonstração, sem Claude Code e sem consumo de IA
 * @param isolatedFeatures  executa features em paralelo, cada uma em um git worktree próprio; desligado, há um único
 *                          escritor por repositório
 */
public record AppSettings(
        String claudePath,
        String defaultModel,
        int maxConcurrentRuns,
        int runTimeoutMinutes,
        Double maxBudgetUsd,
        boolean demoMode,
        boolean isolatedFeatures) {

    public static final AppSettings DEFAULTS = new AppSettings(null, null, 2, 30, null, false, false);

    public AppSettings withDemoMode(boolean on) {
        return new AppSettings(claudePath, defaultModel, maxConcurrentRuns, runTimeoutMinutes, maxBudgetUsd, on, isolatedFeatures);
    }
}
