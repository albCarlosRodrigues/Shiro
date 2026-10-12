package dev.squaddesktop.documents;

/** Quem escreveu uma versão: a pessoa que usa o aplicativo ou um agente (com o id dele). */
public record Author(Type type, String agentId) {

    public enum Type { USER, AGENT }

    public static final Author USER = new Author(Type.USER, null);

    public static Author agent(String agentId) {
        return new Author(Type.AGENT, agentId);
    }
}
