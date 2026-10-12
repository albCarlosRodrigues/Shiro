package dev.squaddesktop.sessions;

public record Message(String id, String sessionId, String runId, Role role, String content, int seq, String createdAt) {

    public enum Role { USER, AGENT, SYSTEM }
}
