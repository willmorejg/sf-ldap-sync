import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Session } from '../core/session';

/** Start page: what can be maintained here, by group. */
@Component({
  selector: 'app-home-page',
  imports: [RouterLink],
  template: `
    <header class="page-head">
      <div>
        <h1>Hello{{ session.me()?.name ? ', ' + session.me()!.name : '' }}</h1>
        <p class="muted">Find and maintain people, accounts and reference data in the Acme Insurance directory.
          Entries kept in step by Salesforce or the CSV feeds are marked, and most of their details are changed at the source.</p>
      </div>
    </header>
    @for (g of groups(); track g.name) {
      <section aria-labelledby="g-{{ $index }}">
        <h2 id="g-{{ $index }}" class="group-title">{{ g.name }}</h2>
        <div class="tiles">
          @for (k of g.kinds; track k.id) {
            <a class="tile" [routerLink]="session.listLink(k)">
              <strong>{{ k.plural }}</strong>
              <span class="muted small">{{ k.description }}</span>
            </a>
          }
        </div>
      </section>
    }
    @if (session.canQuery()) {
      <section aria-labelledby="g-dev">
        <h2 id="g-dev" class="group-title">Developer</h2>
        <div class="tiles">
          <a class="tile" routerLink="/query"><strong>Query builder</strong>
            <span class="muted small">Build LDAP filters, copy them as ldapsearch commands or URLs, and run them</span></a>
        </div>
      </section>
    }
  `,
})
export class HomePage {
  protected readonly session = inject(Session);
  protected readonly groups = computed(() => {
    const kinds = this.session.kinds().filter((k) => !['agreement', 'book', 'permission'].includes(k.id));
    const names = [...new Set(kinds.map((k) => k.group))];
    return names.map((name) => ({
      name,
      kinds: kinds.filter((k) => k.group === name).map((k) =>
        k.id === 'agency' ? { ...k, plural: 'Agencies, agreements and books' } : k),
    }));
  });
}
