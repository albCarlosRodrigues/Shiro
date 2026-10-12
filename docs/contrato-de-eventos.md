# Contrato de eventos

O backend traduz a saída do Claude Code (`--output-format stream-json`) para um contrato interno próprio. É esse
contrato — e não o formato do Claude Code — que é gravado no banco (`run_event`), transmitido à interface e
mostrado nas abas **Conversa**, **Atividade** e **Console**. Trocar de versão do Claude Code exige ajustar só o
parser (`claude/StreamJsonParser.java`); a interface não muda.

Implementação: `backend/src/main/java/dev/squaddesktop/runs/AgentEvent.java`.

## Transporte

`GET /api/events` é um fluxo SSE único para todo o aplicativo.

| Evento SSE | Quando | Dados |
|---|---|---|
| `hello` | ao conectar | `{"cursor": <último id gravado>}` |
| `run` | cada evento | envelope abaixo |
| comentário `ping` | a cada 15 s | — |

Envelope do evento `run`:

```json
{ "id": 1234, "runId": "…", "sessionId": "…", "type": "tool.call", "at": "2026-10-08T11:45:00Z", "data": { … } }
```

- `id` é um cursor crescente. Ao reconectar, o navegador envia `Last-Event-ID` (ou a interface usa `?after=<id>`) e o
  backend reenvia o que faltou, sem lacunas.
- Eventos com `id = 0` são transitórios: não são gravados nem reenviados (`thinking`, `project.changed`).
- Campos opcionais ausentes na origem **ficam de fora** do `data`. Nenhum valor é inventado: custo, tokens e número
  de turnos só aparecem quando o Claude Code os informou.

## Eventos de execução

| `type` | Significado | `data` |
|---|---|---|
| `run.queued` | execução entrou na fila | `kind` (CHAT, PLANNING, EXECUTION), `access` (READ_ONLY, WRITE), `demo` |
| `run.started` | processo iniciou e o Claude Code informou a sessão | `claudeSessionId`, `model`, `cwd`, `permissionMode`, `tools`, `cliVersion`, `resumed` |
| `text.delta` | trecho do texto que o agente está escrevendo | `text` |
| `thinking` | o agente está raciocinando (sem o conteúdo) — transitório | `estimatedTokens` |
| `message` | mensagem completa do agente | `text`, `parentToolUseId`, `messageId` (mensagem gravada na conversa) |
| `tool.call` | chamada de ferramenta | `toolUseId`, `name`, `input`, `parentToolUseId` |
| `tool.result` | resultado de ferramenta | `toolUseId`, `name`, `content` (cortado em 8.000 caracteres), `error`, `truncated` |
| `permission.denied` | ferramenta negada pela política de permissões | `toolName`, `toolUseId`, `input`, `reason`, `suggestedRule` (regra que liberaria, quando segura) |
| `usage` | consumo informado ao fim | `inputTokens`, `outputTokens`, `cacheReadTokens`, `cacheCreationTokens`, `costUsd`, `numTurns`, `durationMs` |
| `notice` | aviso para quem acompanha | `level` (info, warn, error), `code`, `text` |
| `console` | linha bruta para a aba Console | `stream` (stdout, stderr, command), `text` |
| `run.finished` | concluída | `status`, `resultText`, `exitCode`, `failureCode`, `failureMessage`, `permissionDenials` |
| `run.failed` | falhou ou passou do tempo (`status` FAILED ou TIMED_OUT) | idem |
| `run.cancelled` | cancelada por você | idem |
| `run.interrupted` | o aplicativo foi encerrado durante a execução | idem |

Toda execução termina com exatamente um dos quatro eventos finais.

### Estados de uma execução

```
QUEUED → STARTING → RUNNING → COMPLETED
                         ├──→ FAILED | TIMED_OUT
                         └──→ CANCELLING → CANCELLED
(qualquer estado não final) → INTERRUPTED   (aplicativo encerrado ou caiu; marcado na inicialização seguinte)
```

Pausar a fila não muda o estado de nenhuma execução: só impede que novas comecem. Uma execução que falhou nunca é
repetida automaticamente.

### Códigos de falha (`failureCode`)

`claude_not_found`, `claude_unsupported`, `claude_not_authenticated`, `spawn_failed`, `timeout`, `rate_limited`,
`billing`, `budget_exceeded`, `max_turns`, `claude_session_not_found`, `folder_missing`, `claude_error`,
`no_result`, `internal_error`, `app_closed`. A interface traduz cada código em uma explicação e um caminho de
resolução (`frontend/src/app/core/failures.ts`).

## Avisos de projeto

| `type` | Significado | `data` |
|---|---|---|
| `project.changed` | algo do projeto mudou; a interface recarrega a parte indicada — transitório | `projectId`, `scope` (`planning`, `proposals`, `plans`, `features`), `id` opcional |

## Blocos estruturados dos agentes

Na sala de planejamento e nas etapas de feature, os agentes terminam a resposta com blocos JSON cercados. O backend
os separa do texto (o que aparece na conversa) e os transforma em objetos para revisão humana
(`planning/Blocks.java`). JSON inválido vira um aviso visível; nunca é aplicado.

| Bloco | Quem usa | Vira |
|---|---|---|
| ` ```squad-proposta ` | qualquer agente na sala | proposta de alteração de documento (aplicar / descartar / desfazer) |
| ` ```squad-plano ` | comandante na sala | rascunho de plano validado (sprints, features, dependências) |
| ` ```squad-entrega ` | agente executando uma etapa | passagem registrada na etapa (objetivo, contexto, entregas, arquivos, pendências, critérios de validação, testes, próximo agente) |

Formatos completos: veja os prompts em `planning/PlanningService.java#prompt` e `features/FeaturePrompts.java#step`.
