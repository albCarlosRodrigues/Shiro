package dev.squaddesktop.shared;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Acesso tolerante a árvores JSON já convertidas em Map/List (ver {@link JsonCodec#parse(String)}). */
public final class JsonValues {

    private JsonValues() {
    }

    /** Percorre chaves aninhadas; devolve null quando qualquer nível falta ou não é objeto. */
    public static Object at(Object root, String... path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    public static String str(Object root, String... path) {
        Object value = at(root, path);
        return value instanceof String s ? s : null;
    }

    public static String strOr(Object root, String fallback, String... path) {
        String value = str(root, path);
        return value == null ? fallback : value;
    }

    public static Long longValue(Object root, String... path) {
        Object value = at(root, path);
        return value instanceof Number n ? n.longValue() : null;
    }

    public static Integer intValue(Object root, String... path) {
        Object value = at(root, path);
        return value instanceof Number n ? n.intValue() : null;
    }

    public static Double doubleValue(Object root, String... path) {
        Object value = at(root, path);
        return value instanceof Number n ? n.doubleValue() : null;
    }

    public static boolean bool(Object root, String... path) {
        return Boolean.TRUE.equals(at(root, path));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object root, String... path) {
        Object value = at(root, path);
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object root, String... path) {
        Object value = at(root, path);
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public static List<String> strings(Object root, String... path) {
        List<String> out = new ArrayList<>();
        for (Object item : list(root, path)) {
            if (item instanceof String s) {
                out.add(s);
            }
        }
        return out;
    }
}
