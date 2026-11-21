import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Api, EntryList, errorMessage } from '../core/api';
import { Session } from '../core/session';

/** One kind of entry: members, agents, clients, underwriting companies, features… */
@Component({
  selector: 'app-entry-list',
  imports: [RouterLink],
  template: `
    @if (kindDef(); as k) {
      <header class="page-head">
        <div>
          <p class="eyebrow">{{ k.group }}</p>
          <h1>{{ k.plural }}</h1>
          <p class="muted">{{ k.description }}</p>
        </div>
        @if (session.canEdit() && k.baseRdn) {
          <a class="button primary" [routerLink]="['/entry', k.id, 'new']">New {{ k.label.toLowerCase() }}</a>
        }
      </header>
      <form class="toolbar" role="search" (submit)="$event.preventDefault(); load()">
        <label class="sr-only" for="q">Search {{ k.plural.toLowerCase() }}</label>
        <input id="q" type="search" [placeholder]="'Search ' + k.plural.toLowerCase() + ' by name, ID or email'"
               [value]="q()" (input)="q.set($any($event.target).value)">
        <button type="submit">Search</button>
      </form>
      @if (error()) {<p class="alert error" role="alert">{{ error() }}</p>}
      @if (list(); as l) {
        @if (l.items.length) {
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">{{ k.group === 'People' ? 'User name' : 'Name' }}</th>
                  <th scope="col">{{ k.group === 'People' ? 'Full name' : 'Display name' }}</th>
                  @if (k.group === 'People') {<th scope="col">Email</th>}
                  @else {<th scope="col">Description</th>}
                  <th scope="col">Status</th>
                </tr>
              </thead>
              <tbody>
                @for (e of l.items; track e.id) {
                  <tr>
                    <td><a [routerLink]="['/entry', k.id, e.id]">{{ e.rdn }}</a></td>
                    <td>{{ k.group === 'People' ? e.cn : e.displayName }}</td>
                    <td>{{ k.group === 'People' ? e.mail : e.description }}</td>
                    <td>
                      <span class="badges">
                        @if (e.managedBy) {<span class="badge">{{ e.managedBy }}</span>}
                        @if (e.deactivated) {<span class="badge danger">deactivated</span>}
                        @else if (e.locked) {<span class="badge warn">locked</span>}
                      </span>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (l.truncated) {
            <p class="muted small">Showing the first {{ l.limit }}. Search to narrow the list.</p>
          }
        } @else {
          <p class="empty">No {{ k.plural.toLowerCase() }}{{ q() ? ' match “' + q() + '”' : ' yet' }}.</p>
        }
      } @else if (!error()) {
        <p class="muted">Loading…</p>
      }
    } @else {
      <p class="alert error">Unknown kind of entry.</p>
    }
  `,
})
export class EntryListPage {
  protected readonly session = inject(Session);
  private readonly api = inject(Api);

  readonly kind = input.required<string>();
  protected readonly kindDef = computed(() => this.session.kind(this.kind()));
  protected readonly q = signal('');
  protected readonly list = signal<EntryList | null>(null);
  protected readonly error = signal('');

  constructor() {
    effect(() => {
      this.kind();
      this.q.set('');
      this.load();
    });
  }

  protected load(): void {
    this.list.set(null);
    this.error.set('');
    this.api.list(this.kind(), this.q()).subscribe({
      next: (l) => this.list.set(l),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }
}
