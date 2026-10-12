# Squad Desktop

Central desktop para organizar projetos de software e coordenar squads de agentes de IA executados pelo
**Claude Code** instalado na sua máquina. Você descreve o que quer, a squad planeja com você, o plano vira features
com responsáveis, dependências e critérios de aceite, e cada feature só é executada quando você autoriza — e só é
concluída quando você aprova a entrega.

- **Interface**: Angular 22 · **Regras e orquestração**: Java 21 + Spring Boot 4 · **Shell**: Electron 44
- **Dados**: SQLite local (um escritor, migrações versionadas) · **Agentes**: Claude Code (externo, detectado)
- Prioridade Windows; também roda em Linux (AppImage). Interface em português do Brasil.

> O Claude Code **não** vem no instalador. O aplicativo detecta a instalação existente, mostra versão e estado de
> login e usa a autenticação que já está lá. Sem ele, use o **modo demonstração**.

## Sumário

1. [Pré-requisitos](#pré-requisitos)
2. [Começando (Windows)](#começando-windows)
3. [Desenvolvimento](#desenvolvimento)
4. [Instalador](#instalador)
5. [Testes](#testes)
6. [Como usar](#como-usar)
7. [Onde ficam os dados](#onde-ficam-os-dados)
8. [O que foi verificado](#o-que-foi-verificado)
9. [Limitações conhecidas](#limitações-conhecidas)
10. [Documentação](#documentação)

## Pré-requisitos

| Para | Precisa de | Observação |
|---|---|---|
| Usar o app instalado | Windows 10/11 x64 | O instalador traz o runtime Java e a interface. |
| Usar agentes reais | [Claude Code](https://docs.claude.com/en/docs/claude-code) instalado e com login feito (`claude` no terminal) | Versões testadas: 2.1.29x. Recursos ausentes na sua versão são detectados e desativados. |
| Desenvolver/compilar | Node 22.22+ ou 24.15+, JDK 21+, Git | `npm run bootstrap` baixa Node, JDK e Maven portáteis para `.toolchain/` se faltarem. Nada é instalado no sistema. |
| Executar features em paralelo | Git, repositório com ao menos um commit | Opcional (Configurações → Executar features em paralelo, isoladas). |

## Começando (Windows)

No PowerShell ou no Prompt de Comando, dentro desta pasta:

```bat
scripts\bootstrap.cmd        :: ou: npm run bootstrap
npm run build                :: backend + interface + shell + runtime Java (jlink)
npm run dist                 :: gera o instalador em desktop\release\Shiro-Setup-0.1.1.exe
```

O `bootstrap` registra o ambiente em `.toolchain/env-report.json`, baixa só o que falta, instala as dependências e
grava o resultado de cada etapa em `.toolchain/status.json` (logs em `.toolchain/logs/`). Nenhuma etapa interrompe as
outras; se algo falhar, o log indica qual.

## Desenvolvimento

```bash
npm run dev             # app desktop: recompila a interface a cada mudança (Ctrl+R na janela recarrega)
npm run dev -- --browser  # sem Electron: serviço em :4310 e `ng serve` em http://localhost:4200
```

- O modo desktop usa dados separados em `.toolchain/dev-userdata/`; o modo navegador, em `.toolchain/dev-data/`.
- O backend é recompilado automaticamente quando há mudanças em `backend/src/main` (ou com `--rebuild-backend`).
- Estrutura e decisões: [docs/arquitetura.md](docs/arquitetura.md).

| Script | O que faz |
|---|---|
| `npm run bootstrap` | prepara o ambiente (toolchain portátil, dependências, primeira compilação) |
| `npm run dev` | desenvolvimento (desktop ou `--browser`) |
| `npm run build` | build completo; `--skip-tests`, `--offline`, `--fresh-runtime` |
| `npm run dist` | build completo + instalador (Windows: NSIS; Linux: AppImage) |
| `npm test` | verificação completa; `-- --e2e` inclui o teste ponta a ponta do app |

## Instalador

`npm run dist` executa o build completo e o `electron-builder`:

- Windows: `desktop/release/Shiro-Setup-<versão>.exe` (NSIS, x64, permite escolher a pasta de instalação).
- Linux: `desktop/release/*.AppImage`.

O pacote contém o shell Electron, a interface compilada, o serviço (`squad-desktop-backend.jar`) e um runtime Java
reduzido (jlink, ~60 MB, módulos listados em `scripts/build.mjs`). O instalador do Windows precisa ser gerado em
Windows (o NSIS não é gerado a partir do Linux sem Wine). O executável não é assinado: o SmartScreen pode pedir
confirmação na primeira execução.

## Testes

```bash
npm test               # backend (JUnit), interface (Vitest), build de produção, shell (node:test)
npm test -- --e2e      # + ponta a ponta: Electron real + serviço Java real (Playwright)
```

| Conjunto | Cobre |
|---|---|
| Backend — 95 testes | banco real (SQLite em pasta temporária), migrações, transições de estado de execução e de feature, regras de dependência (ciclos com caminho, autodependência, inexistente), validação de plano, parser do `stream-json` com amostras reais, blocos estruturados, menções, propostas com edição concorrente e desfazer, fila (concorrência, um escritor por pasta, pausa, cancelamento), processo que falha, que trava (tempo máximo) e cancelamento de árvore de processos usando um **executável de teste controlado** que imita o Claude Code, detecção/capacidades por versão, regras de permissão sugeridas, git real (linha de base, worktree, merge e conflito abortado), exportação/importação com cópia prévia, fluxo HTTP completo com SSE. |
| Interface — 19 testes | layout (abrir/ocultar/maximizar/restaurar, persistência e leitura tolerante), estado ao vivo a partir de eventos, Markdown sanitizado, busca da paleta. |
| Shell — 11 testes | contrato IPC, validação de argumentos e remetente, layout de recursos empacotado × desenvolvimento, locale UTF-8, redação do token no log. |
| Ponta a ponta — 18 verificações | app abre, token fora da URL, cookie HttpOnly, preload mínimo, 401 sem credencial, pasta de dados com espaços e acentos, fechar com execução ativa pergunta, "Voltar" mantém, sair encerra o Java, execução vira interrompida e não é reiniciada, mensagens e layout restaurados. |

## Como usar

1. **Primeira abertura**: a tela inicial mostra o estado do Claude Code e os primeiros passos. Crie os agentes
   iniciais (comandante, requisitos, arquitetura e operadores Angular, Java, banco de dados e QA) e a squad padrão, ou
   carregue o projeto de exemplo.
2. **Projeto**: nome, cor, grupo, pasta do código e squad. Os documentos (briefing, visão, escopo incluído e
   excluído, glossário, arquitetura, ADRs) ficam no banco do app, com histórico de versões.
3. **Painéis** (área "Painéis"): converse com um agente por sessão, em 1, 2 ou 4 painéis redimensionáveis. Cada
   painel tem Conversa, Atividade (ferramentas, permissões negadas, consumo) e Console (somente leitura). *Conversar*
   é somente leitura; *Executar* autoriza alterações na pasta, mensagem a mensagem.
4. **Planejamento**: escreva o briefing para a squad. Sem menção, responde o comandante; com `@Apelido`, responde
   quem foi chamado (e ele pode chamar outros). Propostas de documentos e planos aparecem para você revisar:
   - proposta → **Aplicar** (vira nova versão do documento, com o agente como autor), **Descartar** ou **Desfazer**;
   - plano → edite sprints e features, veja as pendências apontadas e **Aprove** (cria features *planejadas*).
5. **Features**: quadro ou lista por sprint. **Autorizar** coloca na fila; a feature começa quando as dependências
   estiverem aprovadas. O agente responsável trabalha, registra a passagem (objetivo, entregas, arquivos, testes,
   pendências) e pode passar a vez a outro agente da squad. Na **revisão** você vê a passagem, os arquivos alterados
   com diff e marca os critérios; **Aprovar** conclui e libera as dependentes; **Pedir ajustes** devolve ao responsável
   com o seu comentário. A fila do projeto pode ser pausada sem cancelar o que está rodando.
6. **Configurações**: caminho do Claude Code, teste de conexão, modelo padrão, execuções simultâneas, tempo máximo,
   orçamento por execução, modo demonstração, execução isolada, exportar/importar workspace e cópias do banco.

Atalhos: `Ctrl+K` paleta de comandos · `Ctrl+B` sidebar · `Ctrl+Shift+1/2/3` Painéis/Planejamento/Features ·
`Ctrl+1…4` foco no painel · `Ctrl+Shift+Enter` maximizar · `Ctrl+Shift+N` nova sessão · `Ctrl+Shift+D` documentos ·
`Ctrl+Enter` executa no modo Executar.

## Onde ficam os dados

| O quê | Windows (instalado) | Linux |
|---|---|---|
| Pasta do app | `%APPDATA%\Squad Desktop\` | `~/.config/Squad Desktop/` |
| Banco | `…\data\squad.db` | `…/data/squad.db` |
| Cópias do banco | `…\data\backups\` (antes de migrar, antes de importar, manuais) | idem |
| Worktrees das features isoladas | `…\data\worktrees\<projeto>\<código>\` | idem |
| Logs | `…\logs\backend.log` (token redigido) | idem |

A pasta de código de cada projeto é sua: o app só escreve nela pelas execuções que você autoriza. Excluir um projeto
nunca apaga a pasta. Configurações → *Abrir pasta de dados* mostra o caminho exato.

## O que foi verificado

Relatado separadamente o que foi **simulado** e o que rodou **ao vivo** com o Claude Code autenticado.

**Simulado / automatizado**
- Todos os testes acima passam (backend 95, interface 19, shell 11, ponta a ponta 18/18 no Linux com Xvfb).
- Fluxo completo da interface em modo demonstração, por automação de navegador a 1366×768 e 1920×1080: sala →
  plano → aprovação → autorização → execução → revisão → aprovação → liberação da dependente; proposta de documento.

**Ao vivo (Claude Code 2.1.294, modelo haiku, orçamento por execução US$ 0,50)**
- Conversa somente leitura com a ferramenta `Read`; execução criando arquivo e retomando a mesma sessão do Claude Code;
  cancelamento de execução real em ~3 s, sem custo inventado.
- Permissão negada (`python3 -c …`) → regra sugerida `Bash(python3 *)` → liberada para o agente → pedido explícito
  repetido com sucesso.
- Sala de planejamento real: a menção chamou o operador, que chamou a analista de requisitos; o comandante consolidou
  e entregou um bloco `squad-plano` válido (sem pendências). Plano aprovado; feature autorizada em **modo isolado**
  (worktree `squad/f1`); o agente criou o arquivo e deixou a passagem estruturada; a revisão listou o arquivo novo com
  +1 linha; a aprovação fez commit e `merge --no-ff` na `main`. Etapa de execução: US$ 0,005 informados pelo Claude Code.

**Não verificado**: o instalador NSIS em Windows (precisa ser gerado e executado em Windows — veja abaixo) e o uso
prolongado com projetos grandes.

## Limitações conhecidas

- **Aprovação de permissão em tempo real**: o modo não interativo do Claude Code não oferece um mecanismo documentado
  para aprovar uma ferramenta durante a execução. O app mostra a negação, sugere a regra e você libera e pede de novo.
  Não existe botão "aprovar agora".
- **Console** é um visualizador de logs (stdout/stderr e comando), não um terminal interativo.
- **Custo e tokens** só aparecem quando o Claude Code os informa; o orçamento (`--max-budget-usd`) é a estimativa dele.
- **Paralelismo isolado** exige repositório git com commit; sem git, a execução é sempre uma feature por vez e a revisão
  mostra só a passagem do agente (sem lista de arquivos).
- O merge de uma feature isolada vai para a **branch atual** do repositório; alterações locais não commitadas que se
  sobreponham fazem o git recusar o merge (a mensagem aparece na revisão).
- Uma rodada da sala tem no máximo 6 falas; uma feature encadeia no máximo 4 etapas antes de ir para revisão.
- Importar substitui o workspace inteiro (há cópia automática antes). Preferências da máquina não são exportadas.
- O instalador não é assinado digitalmente.
- Os testes automatizados (inclusive o ponta a ponta) rodaram em Linux. No Windows, rode `npm test -- --e2e` depois do
  `npm run build` para repetir a verificação na sua máquina.

## Documentação

- [docs/arquitetura.md](docs/arquitetura.md) — componentes, segurança local, persistência, adaptador do Claude Code,
  fila, planejamento e features.
- [docs/contrato-de-eventos.md](docs/contrato-de-eventos.md) — eventos SSE, estados, códigos de falha e blocos dos agentes.
- [docs/decisoes.md](docs/decisoes.md) — decisões e versões escolhidas.

## Identidade Shiro e aparência

A interface e o shell usam o nome Shiro. Configurações → Aparência oferece 12 temas, também acessíveis por Ctrl+K → Tema. A escolha é salva no workspace e nas cores da janela nativa para a próxima abertura. Os ícones usam as versões revisadas para cada tamanho e densidade de tela.

A pasta de dados continua sendo `%APPDATA%\Squad Desktop` (ou `SQUAD_USER_DATA`, quando definido), e o identificador do instalador continua `dev.squaddesktop.app`. Não há migração de banco nem de worktrees. O executável, o instalador, a janela e os atalhos usam o ícone Min Light; a marca dentro da interface acompanha o tema.

Validação da integração: 23 testes da interface, 13 testes do shell, 18 verificações ponta a ponta e compilação de produção. A atualização do instalador sobre uma instalação anterior não foi testada. Para abrir a versão compilada: `npm start` na pasta `desktop`, após fechar a instância anterior pelo próprio aplicativo.

### Ícone padrão Min Light

`desktop/resources/icon.png` preserva os bytes da arte Min Light original de 1024 × 1024. `icon.ico` contém 16, 20, 24, 32, 40, 48, 64, 128 e 256 px, com transparência. Windows usa ICO na janela e no empacotamento; splash e configuração Linux usam PNG. A marca dentro do aplicativo continua acompanhando o tema escolhido, com a variante detalhada de 256 px. A origem e o hash estão em `desktop/resources/icon-provenance.json`.
