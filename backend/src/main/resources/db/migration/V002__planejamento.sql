-- Planejamento e entregas: sala de planejamento, propostas de documentos, planos, sprints, features,
-- passagens entre agentes e revisões. Mesmas convenções da V001.

-- Finalidade da sessão: CHAT (painel aberto pela pessoa), PLANNING (vez de um agente na sala de planejamento)
-- e FEATURE (etapa de execução de uma feature).
ALTER TABLE session ADD COLUMN purpose TEXT NOT NULL DEFAULT 'CHAT' CHECK (purpose IN ('CHAT', 'PLANNING', 'FEATURE'));

ALTER TABLE session ADD COLUMN feature_id TEXT;

CREATE INDEX session_purpose ON session (project_id, purpose);

CREATE TABLE planning_message (
    id          TEXT PRIMARY KEY,
    project_id  TEXT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    seq         INTEGER NOT NULL,
    author_type TEXT NOT NULL CHECK (author_type IN ('USER', 'AGENT', 'SYSTEM')),
    agent_id    TEXT,
    content     TEXT NOT NULL,
    mentions    TEXT NOT NULL DEFAULT '[]',
    attachments TEXT NOT NULL DEFAULT '[]',
    run_id      TEXT,
    round_id    TEXT,
    created_at  TEXT NOT NULL
);

CREATE UNIQUE INDEX planning_message_seq ON planning_message (project_id, seq);

-- Uma rodada da sala: quem ainda vai falar, em ordem. Fica gravada para sobreviver a reinícios.
CREATE TABLE planning_round (
    id          TEXT PRIMARY KEY,
    project_id  TEXT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    status      TEXT NOT NULL CHECK (status IN ('ACTIVE', 'DONE', 'STOPPED')),
    pending     TEXT NOT NULL DEFAULT '[]',
    spoken      TEXT NOT NULL DEFAULT '[]',
    current_run TEXT,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE INDEX planning_round_project ON planning_round (project_id, status);

-- Alteração de documento proposta por um agente. Só muda o documento quando a pessoa aplica.
CREATE TABLE document_proposal (
    id              TEXT PRIMARY KEY,
    project_id      TEXT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    document_id     TEXT REFERENCES document (id) ON DELETE CASCADE,
    kind            TEXT NOT NULL,
    title           TEXT NOT NULL,
    content         TEXT NOT NULL,
    summary         TEXT NOT NULL DEFAULT '',
    agent_id        TEXT,
    message_id      TEXT,
    base_version    INTEGER,
    status          TEXT NOT NULL CHECK (status IN ('PENDING', 'APPLIED', 'DISCARDED', 'UNDONE')),
    applied_version INTEGER,
    created_at      TEXT NOT NULL,
    decided_at      TEXT
);

CREATE INDEX document_proposal_project ON document_proposal (project_id, status);

-- Plano proposto (pelo comandante ou pela pessoa). O conteúdo é JSON estruturado; a validação também.
CREATE TABLE plan (
    id            TEXT PRIMARY KEY,
    project_id    TEXT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    number        INTEGER NOT NULL,
    status        TEXT NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'DISCARDED')),
    author_type   TEXT NOT NULL CHECK (author_type IN ('USER', 'AGENT')),
    agent_id      TEXT,
    message_id    TEXT,
    summary       TEXT NOT NULL DEFAULT '',
    content       TEXT NOT NULL,
    validation    TEXT NOT NULL DEFAULT '[]',
    created_at    TEXT NOT NULL,
    updated_at    TEXT NOT NULL,
    approved_at   TEXT
);

CREATE UNIQUE INDEX plan_number ON plan (project_id, number);

CREATE TABLE sprint (
    id         TEXT PRIMARY KEY,
    project_id TEXT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    name       TEXT NOT NULL,
    goal       TEXT NOT NULL DEFAULT '',
    position   INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX sprint_project ON sprint (project_id, position);

CREATE TABLE feature (
    id                  TEXT PRIMARY KEY,
    project_id          TEXT NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    sprint_id           TEXT REFERENCES sprint (id) ON DELETE SET NULL,
    plan_id             TEXT REFERENCES plan (id) ON DELETE SET NULL,
    code                TEXT NOT NULL,
    title               TEXT NOT NULL,
    description         TEXT NOT NULL DEFAULT '',
    priority            TEXT NOT NULL DEFAULT 'MEDIUM' CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    specialty           TEXT NOT NULL DEFAULT '',
    owner_agent_id      TEXT REFERENCES agent (id) ON DELETE SET NULL,
    acceptance_criteria TEXT NOT NULL DEFAULT '[]',
    tasks               TEXT NOT NULL DEFAULT '[]',
    status              TEXT NOT NULL CHECK (status IN ('PLANNED', 'QUEUED', 'IN_PROGRESS', 'IN_REVIEW', 'APPROVED', 'FAILED', 'CANCELLED')),
    revision            INTEGER NOT NULL DEFAULT 0,
    position            INTEGER NOT NULL DEFAULT 0,
    workdir             TEXT,
    branch              TEXT,
    base_commit         TEXT,
    status_note         TEXT,
    created_at          TEXT NOT NULL,
    updated_at          TEXT NOT NULL
);

CREATE UNIQUE INDEX feature_code ON feature (project_id, code);

CREATE INDEX feature_status ON feature (project_id, status);

CREATE TABLE feature_dependency (
    feature_id    TEXT NOT NULL REFERENCES feature (id) ON DELETE CASCADE,
    depends_on_id TEXT NOT NULL REFERENCES feature (id) ON DELETE CASCADE,
    PRIMARY KEY (feature_id, depends_on_id),
    CHECK (feature_id <> depends_on_id)
);

-- Cada etapa de execução de uma feature, com a passagem que o agente deixou para o próximo.
CREATE TABLE feature_step (
    id         TEXT PRIMARY KEY,
    feature_id TEXT NOT NULL REFERENCES feature (id) ON DELETE CASCADE,
    seq        INTEGER NOT NULL,
    revision   INTEGER NOT NULL DEFAULT 0,
    agent_id   TEXT NOT NULL,
    session_id TEXT,
    run_id     TEXT,
    reason     TEXT NOT NULL DEFAULT '',
    status     TEXT NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    handoff    TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX feature_step_seq ON feature_step (feature_id, seq);

CREATE TABLE feature_review (
    id         TEXT PRIMARY KEY,
    feature_id TEXT NOT NULL REFERENCES feature (id) ON DELETE CASCADE,
    revision   INTEGER NOT NULL,
    decision   TEXT NOT NULL CHECK (decision IN ('APPROVED', 'CHANGES_REQUESTED')),
    comment    TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL
);

CREATE INDEX feature_review_feature ON feature_review (feature_id, created_at);

-- Fila de features por projeto. Pausada, nenhuma feature nova começa (as que estão rodando continuam).
CREATE TABLE feature_queue (
    project_id TEXT PRIMARY KEY REFERENCES project (id) ON DELETE CASCADE,
    paused     INTEGER NOT NULL DEFAULT 0,
    reason     TEXT,
    updated_at TEXT NOT NULL
);
