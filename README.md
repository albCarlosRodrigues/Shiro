<p align="center">
  <img src="desktop/resources/icon.png" alt="Ícone do Shiro" width="128" />
</p>

<h1 align="center">Shiro</h1>

<p align="center">
  <strong>Seu projeto, sua squad de IA, um só lugar.</strong><br />
  Organize agentes, transforme ideias em planos e acompanhe cada entrega em um aplicativo desktop.
</p>

<p align="center">
  <a href="#começando">Começando</a> ·
  <a href="#recursos">Recursos</a> ·
  <a href="docs/arquitetura.md">Documentação</a> ·
  <a href="https://github.com/albCarlosRodrigues/Shiro/issues">Bugs e sugestões</a>
</p>

---

## O que é o Shiro

O Shiro é um ambiente desktop para coordenar squads de agentes de IA no desenvolvimento de software. Ele reúne projetos, conversas, documentos, planejamento e revisão de entregas em uma interface em português do Brasil.

Você descreve o objetivo, discute o plano com a squad e aprova as features. Cada execução começa com sua autorização; cada entrega passa pela sua revisão antes de ser concluída. Responsáveis, dependências e critérios de aceite acompanham o trabalho do início ao fim.

Projetos, histórico e preferências ficam em um banco SQLite na sua máquina. Os agentes usam o **Claude Code instalado e autenticado localmente**, com as conexões e o processamento de IA realizados por essa ferramenta.

> **Em desenvolvimento — versão 0.1.1.** O Claude Code é instalado separadamente. Para conhecer o fluxo sem executar agentes reais, use o modo demonstração.

## Plataformas

| Plataforma | Distribuição configurada |
| --- | --- |
| Windows x64 | Plataforma prioritária; instalador NSIS `.exe` |
| Linux | Empacotamento em AppImage |

A interface usa Angular 22; o serviço local, Java 21 e Spring Boot 4; e a janela desktop, Electron 44.

## Recursos

### Organize projetos e squads

- Agrupe projetos e associe cada um à sua pasta de código, cor e squad.
- Configure agentes com papéis, instruções e ferramentas permitidas.
- Comece com modelos de comandante, requisitos, arquitetura, Angular, Java, banco de dados e QA.
- Mantenha briefing, visão, escopo, glossário, arquitetura e decisões com histórico de versões.

### Converse com contexto

- Trabalhe com um agente por sessão em layouts de 1, 2 ou 4 painéis redimensionáveis.
- Acompanhe conversa, atividade, ferramentas, permissões negadas e logs.
- Retome sessões do Claude Code quando a versão instalada oferecer esse recurso.
- Alterne entre **Conversar**, para leitura e análise, e **Executar**, para autorizar alterações na pasta do projeto.

### Planeje com a equipe

- Discuta o briefing em uma sala de planejamento com o comandante e os demais agentes.
- Use `@Apelido` para direcionar uma mensagem a um integrante da squad.
- Revise propostas de documentos antes de aplicar, descartar ou desfazer alterações.
- Transforme planos aprovados em sprints e features com responsáveis, dependências e critérios de aceite.

### Acompanhe a entrega

- Visualize features em quadro ou lista por sprint.
- Autorize o trabalho e deixe a fila respeitar as dependências aprovadas.
- Revise a passagem do agente, os arquivos alterados e o diff quando houver suporte Git.
- Aprove a entrega ou peça ajustes com um comentário.
- Use worktrees para execuções isoladas e controle a concorrência, a pausa da fila e o cancelamento.

### Personalize seu espaço

- Escolha entre 12 temas em **Configurações → Aparência** ou pela paleta de comandos.
- Preserve o layout dos painéis e as preferências do workspace.
- Exporte e importe o workspace e crie cópias do banco.
- Ajuste modelo, tempo máximo, orçamento por execução e quantidade de execuções simultâneas.

## Conceitos principais

| Conceito | Papel no Shiro |
| --- | --- |
| Projeto | Contexto de trabalho com pasta de código, documentos, squad e features. |
| Agente | Perfil com papel, instruções e ferramentas para atuar pelo Claude Code. |
| Squad | Equipe de agentes associada ao projeto. |
| Sessão | Conversa com um agente e seu histórico de execuções. |
| Painel | Área da interface para acompanhar conversa, atividade e console de uma sessão. |
| Plano | Proposta revisável de sprints e features antes de sua aprovação. |
| Feature | Unidade de entrega com responsável, dependências e critérios de aceite. |
| Passagem | Registro do agente com entregas, arquivos, testes e pendências para revisão. |

## Começando

### Pré-requisitos

Para desenvolver, tenha Git e Node.js disponíveis. As versões aceitas de Node são `^22.22.3`, `^24.15.0` ou `>=26.0.0`. O backend precisa de JDK 21 ou superior; o bootstrap prepara ferramentas portáteis em `.toolchain/` quando necessário.

Para usar agentes reais, instale e autentique o Claude Code. O Shiro detecta a instalação existente e também permite informar seu caminho em Configurações.

### Execute a partir do código

```bash
git clone https://github.com/albCarlosRodrigues/Shiro.git
cd Shiro
npm run bootstrap
npm run dev
```

No Windows, você também pode iniciar a preparação com `scripts\bootstrap.cmd`. É necessário ter Node no `PATH` para executar esse script.

O bootstrap instala dependências, prepara o ambiente e registra os resultados em `.toolchain/status.json`, com logs em `.toolchain/logs/`.

### Primeiro projeto

1. Abra o Shiro e confira o estado do Claude Code na tela inicial.
2. Crie os agentes iniciais e a squad padrão, ou carregue o projeto de exemplo em modo demonstração.
3. Cadastre um projeto e selecione sua pasta de código e squad.
4. Converse nos painéis ou leve um briefing para a sala de planejamento.
5. Revise e aprove o plano; depois, autorize as features que deseja executar.
6. Acompanhe cada entrega e aprove o resultado ou peça ajustes.

## Desenvolvimento

| Comando | O que faz |
| --- | --- |
| `npm run bootstrap` | Prepara ferramentas, dependências e primeira compilação. |
| `npm run dev` | Abre o desktop e recompila a interface quando os arquivos mudam. |
| `npm run dev -- --browser` | Inicia a interface no navegador em `http://localhost:4200`, com serviço local em `:4310`. |
| `npm run build` | Compila backend, interface e shell e prepara o runtime Java. |
| `npm run dist` | Compila e gera o instalador para a plataforma. |
| `npm test` | Executa testes e verificações de compilação. |
| `npm test -- --e2e` | Inclui a verificação ponta a ponta com Electron e serviço Java reais. |

No modo desktop, `Ctrl+R` recarrega a janela após a recompilação da interface. O script de desenvolvimento recompila o backend ao iniciar quando detecta mudanças; use `--rebuild-backend` para forçar essa etapa.

Os dados de desenvolvimento ficam separados: `.toolchain/dev-userdata/` no desktop e `.toolchain/dev-data/` no modo navegador.

### Estrutura do projeto

```text
backend/    API local, persistência, integração com Claude Code e orquestração
frontend/   Interface Angular, estado da aplicação, temas e identidade visual
desktop/    Janela Electron, integração nativa e configuração do instalador
scripts/    Preparação do ambiente, desenvolvimento, build e verificação
docs/       Arquitetura, decisões e contrato de eventos
```

### Gere o instalador

```bash
npm run dist
```

Os artefatos são gravados em `desktop/release/`:

- **Windows:** `Shiro-Setup-0.1.1.exe`, com escolha da pasta de instalação.
- **Linux:** arquivo `.AppImage`.

O pacote inclui interface, serviço Java e runtime Java reduzido. O Claude Code continua sendo uma instalação separada. Gere o instalador Windows no próprio Windows; o executável ainda não tem assinatura digital.

O build também aceita `--skip-tests`, `--offline` e `--fresh-runtime`, por exemplo: `npm run build -- --offline`. O modo offline pressupõe que as dependências necessárias já estejam disponíveis.

## Testes e validação

```bash
npm test

# Para executar também o fluxo ponta a ponta:
npm run build
npm test -- --e2e
```

| Conjunto | Cobertura |
| --- | --- |
| Backend — JUnit | SQLite, migrações, dependências, planos, fila, execução, cancelamento, integração Git e fluxo HTTP/SSE. |
| Interface — Vitest | Layout persistente, eventos de sessão, Markdown sanitizado, paleta de comandos e temas. |
| Shell — node:test | Contrato IPC, validação de remetente, recursos, logs e aparência da janela. |
| Ponta a ponta — Playwright | Electron com serviço Java real, autenticação local, encerramento e restauração de estado. |

Na verificação local de **12 de outubro de 2026**, passaram as cinco etapas: testes do backend, testes da interface, build de produção da interface, compilação do shell e testes do shell. O teste ponta a ponta não foi reexecutado nessa rodada.

Validações anteriores registradas no projeto incluem o fluxo em modo demonstração e execuções com Claude Code autenticado. A atualização do instalador sobre uma instalação anterior e o uso prolongado com projetos grandes ainda precisam de validação.

## Dados e controle

O nome exibido é **Shiro**. Para preservar a compatibilidade com os dados existentes, a pasta do aplicativo mantém o nome `Squad Desktop` e o instalador usa o identificador `dev.squaddesktop.app`.

| Conteúdo | Local |
| --- | --- |
| Dados do aplicativo no Windows | `%APPDATA%\Squad Desktop\` |
| Dados do aplicativo no Linux | `~/.config/Squad Desktop/` |
| Banco SQLite | `data/squad.db`, dentro da pasta do aplicativo |
| Cópias do banco | `data/backups/` |
| Worktrees isoladas | `data/worktrees/<projeto>/<código>/` |
| Log do serviço | `logs/backend.log` |

Use **Configurações → Abrir pasta de dados** para encontrar o diretório da instalação. A variável `SQUAD_USER_DATA` permite definir outro local.

O serviço escuta apenas na máquina local, com autenticação por inicialização. O token é entregue à interface por cookie HttpOnly e redigido nos logs. A janela usa isolamento de contexto, e o Markdown é sanitizado antes da exibição.

Excluir um projeto não apaga sua pasta de código. Importar um workspace substitui o workspace atual, com cópia automática prévia do banco; preferências específicas da máquina não são exportadas.

## Atalhos

| Atalho | Ação |
| --- | --- |
| `Ctrl+K` | Abrir a paleta de comandos. |
| `Ctrl+B` | Exibir ou ocultar a barra lateral. |
| `Ctrl+Shift+1/2/3` | Alternar entre Painéis, Planejamento e Features. |
| `Ctrl+1…4` | Focar um painel. |
| `Ctrl+Shift+Enter` | Maximizar ou restaurar o painel. |
| `Ctrl+Shift+N` | Criar uma sessão. |
| `Ctrl+Shift+D` | Abrir documentos. |
| `Ctrl+Enter` | Enviar no modo Executar. |

## Limitações atuais

- A integração de agentes é com Claude Code. Os recursos disponíveis dependem das capacidades detectadas na versão instalada.
- O console exibe logs; não oferece um terminal interativo.
- Permissões negadas exigem liberar a regra para o agente e repetir o pedido. Não há aprovação de ferramenta em tempo real.
- Custos e tokens são exibidos quando informados pelo Claude Code; o orçamento é uma estimativa dessa ferramenta.
- Execuções isoladas exigem Git e pelo menos um commit. O merge usa a branch atual do projeto e pode ser recusado se houver alterações locais conflitantes.
- Uma rodada de planejamento tem até seis falas; uma feature encadeia até quatro etapas antes da revisão.
- Execuções interrompidas não são reiniciadas automaticamente quando o aplicativo abre novamente.

## Documentação

- [Arquitetura](docs/arquitetura.md) — componentes, persistência, segurança local, agentes e fila.
- [Contrato de eventos](docs/contrato-de-eventos.md) — SSE, estados, falhas e blocos estruturados.
- [Decisões técnicas](docs/decisoes.md) — escolhas e versões adotadas.
- [Origem do ícone](desktop/resources/icon-provenance.json) — referência e hash da arte Min Light.

## Bugs e sugestões

Use as [issues do Shiro](https://github.com/albCarlosRodrigues/Shiro/issues) para relatar problemas ou propor melhorias. Inclua o sistema operacional, a versão do aplicativo, os passos para reproduzir e o comportamento esperado. Revise logs antes de anexá-los para evitar expor conteúdo dos seus projetos.

## Licença

O Shiro é distribuído sob a **GNU Affero General Public License v3.0 (AGPL-3.0-only)**. Consulte o texto completo em [LICENSE](LICENSE).

---
