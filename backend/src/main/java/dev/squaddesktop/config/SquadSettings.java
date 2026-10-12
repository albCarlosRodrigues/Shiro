package dev.squaddesktop.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.core.env.Environment;

import dev.squaddesktop.shared.Texts;

/**
 * Parâmetros de inicialização recebidos do shell desktop (variáveis de ambiente) ou do application.properties.
 *
 * @param authToken      segredo desta inicialização; toda chamada à API precisa dele. Nunca é registrado em log.
 * @param readyFile      arquivo onde o serviço anuncia porta e pid quando fica pronto
 * @param parentPid      processo do shell desktop; se ele terminar, o serviço também termina
 * @param staticDir      pasta com a interface compilada, servida na raiz
 * @param allowedOrigins origens extras aceitas (desenvolvimento com ng serve)
 */
public record SquadSettings(String authToken, String dataDir, String readyFile, Long parentPid, String staticDir,
        List<String> allowedOrigins, long demoStepMillis, String appVersion) {

    static SquadSettings from(Environment env) {
        String origins = first(env, "squad.allowed-origins", "SQUAD_ALLOWED_ORIGINS");
        String parent = first(env, "squad.parent-pid", "SQUAD_PARENT_PID");
        String demoDelay = first(env, "squad.demo-step-ms", "SQUAD_DEMO_STEP_MS");
        return new SquadSettings(
                first(env, "squad.auth-token", "SQUAD_AUTH_TOKEN"),
                first(env, "squad.data-dir", "SQUAD_DATA_DIR"),
                first(env, "squad.ready-file", "SQUAD_READY_FILE"),
                parent == null ? null : Long.valueOf(parent),
                first(env, "squad.static-dir", "SQUAD_STATIC_DIR"),
                origins == null ? List.of() : Arrays.stream(origins.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList(),
                demoDelay == null ? 120 : Long.parseLong(demoDelay),
                env.getProperty("squad.version", "0.1.0"));
    }

    private static String first(Environment env, String property, String variable) {
        String value = env.getProperty(property);
        if (Texts.isBlank(value)) {
            value = env.getProperty(variable);
        }
        return Texts.trimToNull(value);
    }
}
