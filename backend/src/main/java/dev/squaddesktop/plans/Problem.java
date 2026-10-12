package dev.squaddesktop.plans;

/**
 * Problema encontrado ao validar um plano. ERROR impede a aprovação; WARNING só alerta.
 *
 * @param feature código da feature envolvida, quando houver
 * @param field   campo envolvido (title, criteria, owner, dependsOn…), quando houver
 */
public record Problem(String severity, String code, String feature, String field, String message) {

    public static Problem error(String code, String feature, String field, String message) {
        return new Problem("ERROR", code, feature, field, message);
    }

    public static Problem warning(String code, String feature, String field, String message) {
        return new Problem("WARNING", code, feature, field, message);
    }

    public boolean blocking() {
        return "ERROR".equals(severity);
    }
}
