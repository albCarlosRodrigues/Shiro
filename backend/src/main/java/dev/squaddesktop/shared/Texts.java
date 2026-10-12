package dev.squaddesktop.shared;

public final class Texts {

    private Texts() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Corta o texto preservando o início; acrescenta marcador quando houve corte. */
    public static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** Mantém apenas o final do texto (útil para a cauda do stderr). */
    public static String tail(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(value.length() - max);
    }

    public static String requireText(String value, String field, int max) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw ApiException.invalid("required", "Preencha o campo " + field + ".");
        }
        if (trimmed.length() > max) {
            throw ApiException.invalid("too_long", "O campo " + field + " aceita até " + max + " caracteres.");
        }
        return trimmed;
    }

    public static String optionalText(String value, String field, int max) {
        String text = orEmpty(value);
        if (text.length() > max) {
            throw ApiException.invalid("too_long", "O campo " + field + " aceita até " + max + " caracteres.");
        }
        return text;
    }
}
