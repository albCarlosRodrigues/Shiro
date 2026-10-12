package dev.squaddesktop.shared;

import java.util.List;
import java.util.regex.Pattern;

public final class Colors {

    /** Paleta sugerida para projetos, grupos e agentes; qualquer #rrggbb é aceito. */
    public static final List<String> PALETTE = List.of(
            "#8fa2ff", "#4cc2b0", "#e9b44c", "#ef7b6c", "#c58af9", "#6fb3e8", "#8ccf7e", "#e58fb6");

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    private Colors() {
    }

    public static String require(String color, String fallback) {
        if (Texts.isBlank(color)) {
            return fallback;
        }
        if (!HEX.matcher(color).matches()) {
            throw ApiException.invalid("invalid_color", "Cor inválida: use o formato #rrggbb.");
        }
        return color.toLowerCase();
    }

    public static String pick(int index) {
        return PALETTE.get(Math.floorMod(index, PALETTE.size()));
    }
}
