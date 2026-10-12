package dev.squaddesktop.plans;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Grafo de dependências entre features: detecta ciclos e calcula uma ordem de execução. */
public final class DependencyGraph {

    private final Map<String, List<String>> edges = new LinkedHashMap<>();

    public DependencyGraph add(String node, List<String> dependsOn) {
        edges.computeIfAbsent(node, k -> new ArrayList<>()).addAll(dependsOn == null ? List.of() : dependsOn);
        for (String dep : dependsOn == null ? List.<String>of() : dependsOn) {
            edges.computeIfAbsent(dep, k -> new ArrayList<>());
        }
        return this;
    }

    /** Ciclos encontrados, cada um como caminho fechado (A → B → A). Cada ciclo aparece uma vez. */
    public List<List<String>> cycles() {
        List<List<String>> found = new ArrayList<>();
        Map<String, Integer> state = new HashMap<>(); // 1 = visitando, 2 = concluído
        Set<Set<String>> seen = new LinkedHashSet<>();
        for (String node : edges.keySet()) {
            visit(node, new ArrayList<>(), state, found, seen);
        }
        return found;
    }

    private void visit(String node, List<String> path, Map<String, Integer> state, List<List<String>> found, Set<Set<String>> seen) {
        Integer s = state.get(node);
        if (s != null && s == 2) {
            return;
        }
        if (s != null && s == 1) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(node), path.size()));
            if (seen.add(new java.util.HashSet<>(cycle))) {
                cycle.add(node);
                found.add(cycle);
            }
            return;
        }
        state.put(node, 1);
        path.add(node);
        for (String dep : edges.getOrDefault(node, List.of())) {
            visit(dep, path, state, found, seen);
        }
        path.remove(path.size() - 1);
        state.put(node, 2);
    }

    /** Ordem em que as dependências vêm antes; null quando há ciclo. */
    public List<String> order() {
        if (!cycles().isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        for (String node : edges.keySet()) {
            place(node, done, out);
        }
        return Collections.unmodifiableList(out);
    }

    private void place(String node, Set<String> done, List<String> out) {
        if (!done.add(node)) {
            return;
        }
        for (String dep : edges.getOrDefault(node, List.of())) {
            place(dep, done, out);
        }
        out.add(node);
    }
}
