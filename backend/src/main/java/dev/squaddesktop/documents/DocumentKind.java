package dev.squaddesktop.documents;

/** Tipos de documento de um projeto. Os que têm {@code singleton} existem exatamente uma vez por projeto. */
public enum DocumentKind {
    BRIEFING("Briefing", true),
    VISION("Visão do produto", true),
    SCOPE_IN("Escopo incluído", true),
    SCOPE_OUT("Escopo excluído", true),
    GLOSSARY("Glossário", true),
    ARCHITECTURE("Arquitetura", true),
    ADR("Decisão arquitetural", false),
    FREE("Documento", false);

    private final String defaultTitle;
    private final boolean singleton;

    DocumentKind(String defaultTitle, boolean singleton) {
        this.defaultTitle = defaultTitle;
        this.singleton = singleton;
    }

    public String defaultTitle() {
        return defaultTitle;
    }

    public boolean singleton() {
        return singleton;
    }
}
