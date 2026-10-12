-- Esquema base do Squad Desktop.
-- Convenções: ids são UUID em texto; datas são ISO-8601 em UTC; listas e objetos ficam em JSON (TEXT);
-- booleanos são INTEGER 0/1. Cada comando termina em ';' no fim da linha (ver Migrator).

CREATE TABLE app_setting (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

-- Estado da interface (abas abertas, larguras, disposição dos painéis), um JSON por chave.
CREATE TABLE ui_state (
    key        TEXT PRIMARY KEY,
    value      TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE project_group (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    color      TEXT NOT NULL,
    position   INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE agent (
    id               TEXT PRIMARY KEY,
    name             TEXT NOT NULL,
    role             TEXT NOT NULL CHECK (role IN ('COMMANDER', 'REQUIREMENTS', 'ARCHITECTURE', 'OPERATOR')),
    specialty        TEXT NOT NULL DEFAULT '',
    icon             TEXT NOT NULL DEFAULT 'agent',
    color            TEXT NOT NULL DEFAULT '#8fa2ff',
    responsibility   TEXT NOT NULL DEFAULT '',
    personality      TEXT NOT NULL DEFAULT '',
    instructions     TEXT NOT NULL DEFAULT '',
    guidelines       TEXT NOT NULL DEFAULT '',
    model            TEXT,
    effort           TEXT,
    permission_mode  TEXT,
    tools            TEXT,
    allowed_tools    TEXT NOT NULL DEFAULT '[]',
    disallowed_tools TEXT NOT NULL DEFAULT '[]',
    template_key     TEXT,
    sample           INTEGER NOT NULL DEFAULT 0,
    archived_at      TEXT,
    created_at       TEXT NOT NULL,
    updated_at       TEXT NOT NULL
);

CREATE TABLE squad (
    id              TEXT PRIMARY KEY,
    name            TEXT NOT NULL,
    description     TEXT NOT NULL DEFAULT '',
    commander_id    TEXT NOT NULL REFERENCES agent (id),
    requirements_id TEXT NOT NULL REFERENCES agent (id),
    architecture_id TEXT NOT NULL REFERENCES agent (id),
    sample          INTEGER NOT NULL DEFAULT 0,
    archived_at     TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE TABLE squad_operator (
    squad_id TEXT NOT NULL REFERENCES squad (id) ON DELETE CASCADE,
    agent_id TEXT NOT NULL REFERENCES agent (id),
    position INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (squad_id, agent_id)
);

CREATE INDEX squad_operator_agent ON squad_operator (agent_id);

-- folder_path é a pasta em que os agentes trabalham; o banco e as configurações do aplicativo ficam fora dela.
CREATE TABLE project (
    id             TEXT PRIMARY KEY,
    group_id       TEXT REFERENCES project_group (id) ON DELETE SET NULL,
    squad_id       TEXT REFERENCES squad (id) ON DELETE SET NULL,
    name           TEXT NOT NULL,
    color          TEXT NOT NULL,
    folder_path    TEXT,
    position       INTEGER NOT NULL DEFAULT 0,
    sample         INTEGER NOT NULL DEFAULT 0,
    archived_at    TEXT,
    last_opened_at TEXT,
    created_at     TEXT NOT NULL,
    updated_at     TEXT NOT NULL
);

CREATE INDEX project_group_idx ON project (group_id);

-- Documentos do projeto. Os tipos fixos existem uma vez por projeto; ADR e LIVRE podem se repetir.
CREATE TABLE document (
    id              TEXT PRIMARY KEY,
    project_id      TEXT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    kind            TEXT NOT NULL CHECK (kind IN ('BRIEFING', 'VISION', 'SCOPE_IN', 'SCOPE_OUT', 'GLOSSARY', 'ARCHITECTURE', 'ADR', 'FREE')),
    title           TEXT NOT NULL,
    content         TEXT NOT NULL DEFAULT '',
    version         INTEGER NOT NULL DEFAULT 1,
    position        INTEGER NOT NULL DEFAULT 0,
    updated_by_type TEXT NOT NULL DEFAULT 'USER' CHECK (updated_by_type IN ('USER', 'AGENT')),
    updated_by_id   TEXT,
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

CREATE INDEX document_project ON document (project_id, kind, position);

CREATE UNIQUE INDEX document_singleton ON document (project_id, kind)
    WHERE kind IN ('BRIEFING', 'VISION', 'SCOPE_IN', 'SCOPE_OUT', 'GLOSSARY', 'ARCHITECTURE');

CREATE TABLE document_version (
    document_id TEXT NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    version     INTEGER NOT NULL,
    title       TEXT NOT NULL,
    content     TEXT NOT NULL,
    author_type TEXT NOT NULL CHECK (author_type IN ('USER', 'AGENT')),
    author_id   TEXT,
    note        TEXT,
    created_at  TEXT NOT NULL,
    PRIMARY KEY (document_id, version)
);

-- Uma sessão é a conversa de um agente dentro de um projeto; corresponde a um painel da área de trabalho.
CREATE TABLE session (
    id                TEXT PRIMARY KEY,
    project_id        TEXT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    agent_id          TEXT NOT NULL REFERENCES agent (id),
    title             TEXT NOT NULL,
    claude_session_id TEXT,
    position          INTEGER NOT NULL DEFAULT 0,
    archived_at       TEXT,
    created_at        TEXT NOT NULL,
    updated_at        TEXT NOT NULL
);

CREATE INDEX session_project ON session (project_id, position);

CREATE INDEX session_agent ON session (agent_id);

CREATE TABLE run (
    id                    TEXT PRIMARY KEY,
    session_id            TEXT NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    project_id            TEXT NOT NULL,
    agent_id              TEXT NOT NULL,
    kind                  TEXT NOT NULL CHECK (kind IN ('CHAT', 'PLANNING', 'EXECUTION')),
    access                TEXT NOT NULL CHECK (access IN ('READ_ONLY', 'WRITE')),
    status                TEXT NOT NULL CHECK (status IN ('QUEUED', 'STARTING', 'RUNNING', 'CANCELLING', 'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'INTERRUPTED')),
    demo                  INTEGER NOT NULL DEFAULT 0,
    prompt                TEXT NOT NULL,
    cwd                   TEXT,
    command               TEXT,
    claude_session_id     TEXT,
    model                 TEXT,
    cli_version           TEXT,
    exit_code             INTEGER,
    failure_code          TEXT,
    failure_message       TEXT,
    result_text           TEXT,
    cost_usd              REAL,
    input_tokens          INTEGER,
    output_tokens         INTEGER,
    cache_read_tokens     INTEGER,
    cache_creation_tokens INTEGER,
    num_turns             INTEGER,
    duration_ms           INTEGER,
    permission_denials    TEXT NOT NULL DEFAULT '[]',
    queued_at             TEXT NOT NULL,
    started_at            TEXT,
    finished_at           TEXT
);

CREATE INDEX run_session ON run (session_id, queued_at);

CREATE INDEX run_status ON run (status);

CREATE TABLE message (
    id         TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES session (id) ON DELETE CASCADE,
    run_id     TEXT REFERENCES run (id) ON DELETE SET NULL,
    role       TEXT NOT NULL CHECK (role IN ('USER', 'AGENT', 'SYSTEM')),
    content    TEXT NOT NULL,
    seq        INTEGER NOT NULL,
    created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX message_session_seq ON message (session_id, seq);

-- Eventos de execução no contrato interno (ver docs/contrato-de-eventos.md). O id crescente é o cursor do SSE.
CREATE TABLE run_event (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id     TEXT NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    session_id TEXT NOT NULL,
    type       TEXT NOT NULL,
    payload    TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX run_event_run ON run_event (run_id, id);
