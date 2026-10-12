# Decisões

Registro curto das escolhas que moldam o projeto. Cada item diz o que foi decidido e por quê.

## Versões

| Componente | Versão | Motivo |
|---|---|---|
| Java | 21 (LTS) | LTS com threads virtuais (uma por execução e por transmissão SSE sem pool dedicado). |
| Spring Boot | 4.1.x (Jackson 3) | Linha atual; só o starter web MVC — nada de JPA/Hibernate, o acesso ao SQLite é JDBC explícito. |
| SQLite | `sqlite-jdbc` gerenciado pelo Spring Boot | Banco embutido, arquivo único, sem serviço extra. |
| Angular | 22.2, standalone, signals, zoneless, OnPush | Requisito do projeto; signals simplificam o estado ao vivo vindo do SSE. |
| Electron | 44 | Linha atual com `titleBarOverlay`, fuses e sandbox de preload. |
| Testes | JUnit 5 + AssertJ; Vitest (`@angular/build:unit-test`); `node:test`; Playwright (`_electron`) | Ferramentas padrão de cada camada. |

## Arquitetura

- **Regra de negócio no Java, não no Electron nem no Angular.** O Electron só cuida de janela, diálogos e ciclo do
  serviço; a interface só apresenta. Assim tudo o que importa é testável sem interface.
- **Classes de regra sem Spring.** Montadas em `CoreConfiguration`; os testes de integração criam o núcleo inteiro
  sobre um banco temporário em milissegundos (`testsupport/Fixture`).
- **Um escritor no SQLite.** Uma conexão de escrita serializada e transações curtas evitam `SQLITE_BUSY` e deixam a
  ordem das gravações previsível. Processos longos (Claude Code, git) nunca rodam dentro de transação.
- **Contrato de eventos próprio.** O formato do Claude Code muda entre versões; a interface depende só do contrato
  interno (`docs/contrato-de-eventos.md`).
- **SSE em vez de WebSocket.** Fluxo de mão única, reconexão nativa com `Last-Event-ID` e reenvio do que faltou a
  partir do banco.
- **Token por inicialização em cookie HttpOnly.** O renderer nunca vê o token; ele não aparece em URL nem em log.

## Claude Code

- **Prompt por stdin, argumentos separados, `cwd` explícito.** Evita problemas de aspas e de caminhos com espaços e
  acentos no Windows e impede injeção de argumentos.
- **Capacidades lidas do `--help`.** Nenhuma flag é usada sem que a versão instalada a ofereça.
- **Somente leitura fora de execução autorizada.** Conversa e planejamento usam `dontAsk` com ferramentas de leitura.
  Execução é escolhida mensagem a mensagem (painéis) ou por autorização explícita da feature.
- **Sem `bypassPermissions` por padrão e sem botão de aprovação fictício.** O modo `-p` não tem aprovação interativa
  documentada; a saída honesta é mostrar a negação, sugerir a regra mínima e deixar a pessoa liberar e pedir de novo.
- **Sem nova tentativa automática.** Execuções podem ter efeito em arquivos; repetir é sempre uma decisão humana.

## Planejamento e entregas

- **Blocos JSON cercados (`squad-proposta`, `squad-plano`, `squad-entrega`).** Simples para o modelo produzir,
  fáceis de separar do texto e validados no backend antes de virar qualquer coisa. JSON inválido vira aviso, nunca ação.
- **Plano validado no backend**, com erros (impedem aprovação) e avisos (não impedem). Ciclos são mostrados com o caminho.
- **Aprovar plano ≠ autorizar execução.** São dois passos distintos e explícitos.
- **Só a revisão humana conclui uma feature** e libera as dependentes.
- **Linha de base por retrato da árvore com índice temporário.** Mostra exatamente o que a feature mudou, inclusive
  arquivos novos, sem tocar no índice, no stash nem no histórico da pessoa.
- **Paralelismo só com isolamento.** Sem worktree, um escritor por projeto. Com worktree, a integração é um merge
  revisado; conflitos abortam o merge e são mostrados.
- **Reinício pausa filas.** Depois de uma queda, nada recomeça sozinho.
