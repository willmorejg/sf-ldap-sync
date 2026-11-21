import { inject } from '@angular/core';
import { CanActivateFn, Routes } from '@angular/router';
import { Session } from './core/session';
import { AccountsBrowser } from './pages/accounts-browser';
import { AuditPage } from './pages/audit-page';
import { EntryListPage } from './pages/entry-list';
import { EntryPage } from './pages/entry-page';
import { HomePage } from './pages/home-page';
import { QueryPage } from './pages/query-page';
import { SignedOutPage } from './pages/signed-out-page';

const canQuery: CanActivateFn = () => inject(Session).canQuery();

export const routes: Routes = [
  { path: 'signed-out', component: SignedOutPage, title: 'Signed out · InsureDirectory' },
  { path: '', component: HomePage, title: 'InsureDirectory' },
  { path: 'people/:kind', component: EntryListPage, title: 'People · InsureDirectory' },
  { path: 'accounts/agencies', component: AccountsBrowser, title: 'Accounts · InsureDirectory' },
  { path: 'accounts/:kind', component: EntryListPage, title: 'Accounts · InsureDirectory' },
  { path: 'reference/:kind', component: EntryListPage, title: 'Reference data · InsureDirectory' },
  { path: 'entry/:kind/new', component: EntryPage, title: 'New entry · InsureDirectory' },
  { path: 'entry/:kind/:id', component: EntryPage, title: 'Entry · InsureDirectory' },
  { path: 'query', component: QueryPage, canActivate: [canQuery], title: 'Query builder · InsureDirectory' },
  { path: 'audit', component: AuditPage, title: 'Audit · InsureDirectory' },
  { path: '**', redirectTo: '' },
];
