# Arquitetura

O Squad Desktop é um monorepositório com três partes que rodam juntas na máquina da pessoa:

```
┌──────────────────────────── Electron (desktop/) ─────────────────────────────┐
│ janela, diálogos nativos, ciclo de vida do serviço Java, preload mínimo       │
│   ┌──────────── Interface Angular (frontend/) ────────────┐                   │
│   │ renderer isolado (contextIsolation, sandbox, CSP)     │ ◄── window.squadDesktop (preload tipado)
│   └──────────────▲─────────────────────────▲──────────────┘                   │
└──────────────────┼─────────────────────────┼──────────────────────────────────┘
          HTTP/JSON │  (cookie HttpOnly)  SSE │ /api/events
┌──────────────────┴─────────────────────────┴──── Spring Boot (backend/) ──────┐
│ regras de negócio, fila de execuções, orquestração, persistência              │
│   SQLite (um escritor, WAL, migrações)   ProcessBuilder ──► Claude Code (CLI) │
│                                          ProcessBuilder ──► git              │
└───────────────────────────────────────────────────────────────────────────────┘
```

- **Electron** é um invólucro fino: abre a janela, oferece diálogos nativos (escolher pasta, salvar/abrir arquivo),
  inicia e encerra o serviço Java e guarda o estado da janela. Não contém regra de negócio.
- **Angular** é a interface. Fala só com a API local; estado organizado por domínio em `state/` (signals), fora dos
  componentes, para que ocultar um painel nunca interrompa nada.
- **Java/Spring Boot** concentra tudo o que importa: projetos, documentos, agentes, squads, sessões, fila,
  execução do Claude Code, planejamento, planos, features, revisão, backups. É testável sem Spring (as classes de regra
  são montadas à mão em `config/CoreConfiguration.java`).

## Comunicação e segurança local

| Item | Decisão |
|---|---|
| Endereço | `127.0.0.1`, porta dinâmica escolhida pelo sistema (`SQUAD_PORT=0`). |
| Prontidão | O serviço grava um arquivo de prontidão (`SQUAD_READY_FILE`) com porta e PID; o Electron espera o arquivo e chama `/api/health`. |
| Autenticação | Token aleatório por inicialização, entregue ao Java por variável de ambiente e à interface como **cookie HttpOnly** gravado pelo Electron na partição da janela. Nunca vai em URL nem em log (o log do serviço passa por redação). |
| Mutação | Métodos que alteram dados exigem o cabeçalho `X-Squad-Client` (não pode ser enviado por formulários de outros sites). |
| Host/Origin | `Host` precisa ser loopback (contra DNS rebinding); `Origin` precisa ser a própria ou uma liberada em desenvolvimento. |
| Renderer | `contextIsolation`, `sandbox`, `nodeIntegration` desligado, partição não persistente, CSP restritiva servida pelo backend, navegação e novas janelas bloqueadas (links externos abrem no navegador do sistema). |
| IPC | Canais fixos (`ipc-contract.ts`), argumentos validados e remetente conferido (só a origem do serviço local). |
| Conteúdo | Markdown de agentes e documentos é tratado como não confiável: `marked` + `DOMPurify`. |
| Fuses | `electronFuses` desliga `RunAsNode`, `NODE_OPTIONS`, inspeção por argumentos e ativa a validação do `asar`. |

## Persistência

- SQLite via JDBC (`sqlite-jdbc`), arquivo `squad.db` na pasta de dados do aplicativo — separada das pastas de código
  dos projetos. Excluir um projeto nunca apaga a pasta dele.
- **Um escritor**: todas as gravações passam por uma única conexão com transações curtas (`Database.write`); leituras
  usam um pool pequeno. WAL ativo, `busy_timeout`, chaves estrangeiras ligadas.
- **Migrações versionadas** (`db/migration/V00N__*.sql`) com checksum. Antes de migrar um banco existente o serviço
  grava uma cópia em `backups/`.
- Estado da interface (abas, larguras, layout, área escolhida de cada projeto) fica na tabela `ui_state`.

## Execução do Claude Code

`claude/` contém o adaptador real:

1. **Descoberta** (`ClaudeLocator`): caminho configurado, `PATH`, locais de instalação conhecidos (npm global, instalador
   nativo, `%LOCALAPPDATA%`…). A detecção (o executável existe e responde `--version`) é separada da autenticação
   (`claude auth status`), e o resultado é mostrado em Configurações.
2. **Capacidades** (`ClaudeCapabilities`): lidas do `--help` da versão instalada. Cada flag só é usada se existir
   (`--include-partial-messages`, `--permission-prompts`, `--max-budget-usd`, `--effort`, `--session-id`, `--tools`,
   `--append-system-prompt-file`…).
3. **Comando** (`ClaudeCommandBuilder`): argumentos separados (nunca uma linha de shell), `cwd` explícito, prompt pelo
   **stdin**, instruções do agente por arquivo temporário. Usa a autenticação já existente do Claude Code; o app não
   pede nem guarda senha.
4. **Execução** (`ClaudeRunExecutor`): lê stdout e stderr em paralelo, converte o `stream-json` em eventos do
   [contrato](contrato-de-eventos.md), aplica tempo máximo e cancelamento (encerra a árvore de processos — `taskkill /T`
   no Windows, grupo de processos fora dele).
5. **Sessões**: o `session_id` do Claude Code é guardado e retomado com `--resume` só quando a versão oferece suporte.

### Permissões

| Tipo de pedido | Modo | Ferramentas |
|---|---|---|
| Conversa (CHAT) e sala de planejamento (PLANNING) | `dontAsk` (ou `plan`, se o agente pede) | somente leitura: `Read`, `Glob`, `Grep` (+ `WebSearch`/`WebFetch` se o agente as tiver) |
| Execução (EXECUTION), sempre pedida explicitamente | o do agente (padrão `acceptEdits`) | as do agente + regras liberadas (`allowedTools`) |

- O aplicativo **nunca** usa `bypassPermissions` por padrão.
- O modo não interativo não tem um mecanismo documentado de aprovação em tempo real. Por isso não há botão
  "aprovar agora": quando o Claude Code nega uma ferramenta, o evento `permission.denied` traz a regra que a liberaria
  (por exemplo `Bash(python3 *)`, só quando o comando não tem operadores de shell). A pessoa libera a regra para aquele
  agente e pede de novo — explicitamente.

## Fila e orquestração (`runs/`)

- No máximo `maxConcurrentRuns` execuções simultâneas; uma por sessão; e só **uma execução que escreve por pasta**.
- Pausar a fila impede novos inícios; cancelar encerra uma execução; falha é um estado próprio. Nada que falhou é
  repetido sozinho.
- Na inicialização, execuções sem desfecho viram `INTERRUPTED`; nada é reiniciado.
- Ouvintes (`RunListener`) recebem o fim de cada execução: é assim que a sala de planejamento e as features avançam.

## Planejamento, planos e features

```
sala de planejamento ──(squad-proposta)──► proposta de documento ──aplicar/descartar/desfazer──► documento versionado
        │
        └──(squad-plano do comandante)──► plano (rascunho validado) ──aprovar──► sprints + features PLANEJADAS
                                                                                      │ autorizar (explícito)
                                                                                      ▼
                                       fila do projeto ─► etapa (Claude Code, EXECUTION) ─(squad-entrega)─► passagem
                                                             ▲          │ próximo agente (até 4 em cadeia)
                                                             │          ▼
                                                  pedir ajustes ◄── REVISÃO HUMANA ──aprovar──► APROVADA (libera dependentes)
```

- **Sala** (`planning/PlanningService`): cada mensagem abre uma rodada. Falam os mencionados (ou o comandante); quem
  fala pode chamar outro com `@apelido`. Até 6 falas por rodada. As falas rodam como `PLANNING` (somente leitura).
  Planejar nunca inicia implementação.
- **Propostas** (`planning/ProposalService`): guardam a versão do documento que o agente viu. Se a pessoa editou o
  documento depois, aplicar exige confirmação e mostra as duas versões; a versão humana continua no histórico. Desfazer
  só acontece se ninguém mexeu no documento depois da aplicação.
- **Planos** (`plans/PlanService`): validados no backend a cada alteração — dependências circulares (com caminho),
  códigos repetidos, critérios de aceite ausentes, responsável desconhecido ou fora da squad, prioridade inválida,
  dependência inexistente. Erros impedem a aprovação; avisos não.
- **Features** (`features/`): estados explícitos
  `PLANNED → QUEUED → IN_PROGRESS → IN_REVIEW → APPROVED`, além de `FAILED` e `CANCELLED`. Só a aprovação humana
  conclui e libera dependentes. Pausar a fila do projeto ≠ cancelar ≠ falhar ≠ aguardar revisão.
- **Execução isolada** (opcional, Configurações): sem ela, um único escritor por projeto (uma feature por vez, na
  pasta do projeto). Com ela, em repositórios git com commits, cada feature roda em um `git worktree` próprio
  (`<dados>/worktrees/<projeto>/<código>`, branch `squad/<código>`), em paralelo até o limite de execuções. Aprovar faz
  commit na branch da feature e `merge --no-ff` na branch atual; conflito aborta o merge e é mostrado na revisão.
- **Alterações na revisão**: a linha de base é um retrato da árvore de arquivos gravado com um índice git temporário
  (não toca no índice nem no histórico da pessoa); a revisão compara esse retrato com o estado atual, incluindo arquivos
  novos. Pastas que não são repositório git mostram só a passagem do agente.
- **Reinício**: filas com features pendentes ficam **pausadas** até a pessoa retomar; etapas que estavam rodando viram
  falha "interrompida", sem nova tentativa.

## Encerramento

Ao fechar a janela o Electron consulta `/api/system/active-work`. Havendo trabalho, pergunta se deve cancelar e sair
ou voltar. Ao sair, pede `/api/system/shutdown` (o serviço cancela execuções, encerra os processos do Claude Code e
fecha o banco) e, se o serviço não terminar no prazo, encerra a árvore de processos. O serviço também vigia o PID do
Electron e se encerra se ele sumir.

## Modo demonstração

Executor determinístico (`demo/`), sem Claude Code e sem consumo, identificado na barra superior. Gera as mesmas
sequências de eventos e os mesmos blocos estruturados (plano, proposta, passagem) para que o fluxo inteiro possa ser
conhecido e testado. Dados de exemplo ("Livraria Aurora (exemplo)") são marcados como exemplo.

## Estrutura de pastas

```
backend/   Spring Boot (Java 21): agents, claude, config, demo, documents, features, persistence, planning, plans,
           projects, runs, sessions, settings, shared, squads, system, web, workspace
frontend/  Angular 22: core (API, SSE, Markdown, bridge), state (stores), shell (barra, sidebar, paleta),
           features (home, workspace, planning, delivery, documents, agents, squads, settings)
desktop/   Electron 44: main, preload, contrato IPC, ciclo do serviço, testes e teste ponta a ponta
scripts/   bootstrap, dev, build (jlink + instalador), verify
docs/      esta documentação
```
