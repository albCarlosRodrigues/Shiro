/** Explicação e caminho de resolução para cada código de falha de execução (ver FailureCodes no backend). */
export interface FailureHelp {
  title: string;
  fix: string;
  action?: 'settings' | 'project' | 'activity' | 'retry';
}

const HELP: Record<string, FailureHelp> = {
  claude_not_found: {
    title: 'Claude Code não encontrado',
    fix: 'Instale o Claude Code ou informe o caminho do executável em Configurações.',
    action: 'settings',
  },
  claude_unsupported: {
    title: 'Versão do Claude Code sem suporte',
    fix: 'Atualize com “claude update” e use “Verificar de novo” em Configurações.',
    action: 'settings',
  },
  claude_not_authenticated: {
    title: 'Claude Code sem login',
    fix: 'Abra um terminal, rode “claude” uma vez e faça o login. Depois use “Testar conexão” em Configurações.',
    action: 'settings',
  },
  spawn_failed: {
    title: 'Não foi possível iniciar o Claude Code',
    fix: 'Confira o caminho do executável em Configurações e se o antivírus não bloqueou o processo.',
    action: 'settings',
  },
  timeout: {
    title: 'Tempo máximo atingido',
    fix: 'A execução foi encerrada pelo limite de tempo. Aumente o limite em Configurações ou divida a tarefa.',
    action: 'settings',
  },
  rate_limited: {
    title: 'Limite de uso atingido',
    fix: 'O provedor recusou por limite de uso. Aguarde a janela de uso renovar e envie de novo.',
  },
  billing: {
    title: 'Problema de cobrança na conta',
    fix: 'Verifique o plano ou os créditos da conta usada pelo Claude Code.',
  },
  budget_exceeded: {
    title: 'Orçamento da execução atingido',
    fix: 'A execução parou no teto de custo configurado. Ajuste o orçamento em Configurações se quiser continuar.',
    action: 'settings',
  },
  max_turns: {
    title: 'Limite de turnos atingido',
    fix: 'O agente usou todos os turnos permitidos. Peça para continuar ou divida a tarefa.',
    action: 'retry',
  },
  claude_session_not_found: {
    title: 'Contexto anterior não encontrado',
    fix: 'O Claude Code não encontrou a conversa anterior. Use “Recomeçar contexto” no menu do painel e envie de novo.',
  },
  folder_missing: {
    title: 'Pasta do projeto não encontrada',
    fix: 'A pasta foi movida ou removida. Edite o projeto e escolha a pasta correta.',
    action: 'project',
  },
  folder_required: {
    title: 'Projeto sem pasta',
    fix: 'Para executar tarefas que alteram arquivos, defina a pasta do projeto.',
    action: 'project',
  },
  claude_error: {
    title: 'O Claude Code informou um erro',
    fix: 'Veja a aba Console para a saída completa do processo.',
    action: 'activity',
  },
  no_result: {
    title: 'O processo terminou sem resultado',
    fix: 'O Claude Code saiu sem entregar a resposta final. Veja a aba Console.',
    action: 'activity',
  },
  app_closed: {
    title: 'Interrompida pelo fechamento do aplicativo',
    fix: 'Nada foi repetido automaticamente. Confira os arquivos do projeto e, se quiser, peça de novo.',
    action: 'retry',
  },
  internal_error: {
    title: 'Erro interno',
    fix: 'Veja o log do serviço local em Configurações > Diagnóstico.',
    action: 'settings',
  },
};

export function failureHelp(code: string | null | undefined): FailureHelp | null {
  if (!code) return null;
  return HELP[code] ?? { title: 'Falha na execução', fix: 'Veja as abas Atividade e Console para detalhes.', action: 'activity' };
}
