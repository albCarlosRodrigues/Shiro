import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./features/home/home.page').then(m => m.HomePage), title: 'Início · Shiro' },
  { path: 'projeto/:id', loadComponent: () => import('./features/workspace/workspace.page').then(m => m.WorkspacePage) },
  { path: 'agentes', loadComponent: () => import('./features/agents/agents.page').then(m => m.AgentsPage), title: 'Agentes · Shiro' },
  { path: 'squads', loadComponent: () => import('./features/squads/squads.page').then(m => m.SquadsPage), title: 'Squads · Shiro' },
  {
    path: 'configuracoes',
    loadComponent: () => import('./features/settings/settings.page').then(m => m.SettingsPage),
    title: 'Configurações · Shiro',
  },
  { path: '**', redirectTo: '' },
];
