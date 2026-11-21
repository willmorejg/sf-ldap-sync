import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Api, EntryList, EntrySummary, errorMessage } from '../core/api';
import { Session } from '../core/session';

interface Column {
  kind: 'agency' | 'agreement' | 'book';
  title: string;
  list: EntryList | null;
  selected: string | undefined;
}

/** The account tree as three columns: agencies, the chosen agency's agreements, the chosen agreement's books. */
@Component({
  selector: 'app-accounts-browser',
  imports: [RouterLink],
  template: `
    <header class="page-head">
      <div>
        <p class="eyebrow">Accounts</p>
        <h1>Agencies, agreements and books</h1>
        <p class="muted">Choose an agency to see its agreements, and an agreement to see its books.</p>
      </div>
      @if (session.canEdit()) {
        <a class="button primary" [routerLink]="['/entry', 'agency', 'new']">New agency</a>
      }
    </header>
    <form class="toolbar" role="search" (submit)="$event.preventDefault(); loadAgencies()">
      <label class="sr-only" for="aq">Search agencies</label>
      <input id="aq" type="search" placeholder="Search agencies by number or name" [value]="q()"
             (input)="q.set($any($event.target).value)">
      <button type="submit">Search</button>
    </form>
    @if (error()) {<p class="alert error" role="alert">{{ error() }}</p>}
    <div class="columns">
      @for (c of columns(); track c.kind) {
        <section class="column card" [attr.aria-label]="c.title">
          <header class="section-head">
            <h2>{{ c.title }}</h2>
            @if (c.list?.parentId && session.canEdit()) {
              <a class="button small" [routerLink]="['/entry', c.kind, 'new']" [queryParams]="{ parent: c.list!.parentId }">New</a>
            }
          </header>
          @if (c.list === null) {
            <p class="muted small">{{ c.kind === 'agency' ? 'Loading…' : 'Choose ' + (c.kind === 'agreement' ? 'an agency' : 'an agreement') + '.' }}</p>
          } @else if (!c.list.items.length) {
            <p class="muted small">None.</p>
          } @else {
            <ul class="pick-list">
              @for (e of c.list.items; track e.id) {
                <li [class.selected]="e.id === c.selected">
                  <button type="button" (click)="choose(c.kind, e)" [attr.aria-pressed]="e.id === c.selected">
                    <strong>{{ e.rdn }}</strong>
                    @if (e.displayName) {<span class="muted">{{ e.displayName }}</span>}
                    @if (e.underwritingCompany) {<span class="small">UW: {{ e.underwritingCompany }}</span>}
                    @if (e.managedBy) {<span class="badge small">{{ e.managedBy }}</span>}
                  </button>
                  <a class="open" [routerLink]="['/entry', c.kind, e.id]" [attr.aria-label]="'Open ' + e.rdn">Open ›</a>
                </li>
              }
            </ul>
            @if (c.list.truncated) {<p class="muted small">First {{ c.list.limit }} shown; search to narrow.</p>}
          }
        </section>
      }
    </div>
  `,
})
export class AccountsBrowser {
  protected readonly session = inject(Session);
  private readonly api = inject(Api);
  private readonly router = inject(Router);

  readonly agency = input<string>();
  readonly agreement = input<string>();

  protected readonly q = signal('');
  protected readonly error = signal('');
  private readonly agencies = signal<EntryList | null>(null);
  private readonly agreements = signal<EntryList | null>(null);
  private readonly books = signal<EntryList | null>(null);

  protected readonly columns = computed<Column[]>(() => [
    { kind: 'agency', title: 'Agencies', list: this.agencies(), selected: this.agency() },
    { kind: 'agreement', title: 'Agreements', list: this.agreements(), selected: this.agreement() },
    { kind: 'book', title: 'Books', list: this.books(), selected: undefined },
  ]);

  constructor() {
    this.loadAgencies();
    effect(() => this.loadChildren('agreement', this.agency(), this.agreements));
    effect(() => this.loadChildren('book', this.agreement(), this.books));
  }

  protected loadAgencies(): void {
    this.api.list('agency', this.q()).subscribe({
      next: (l) => this.agencies.set(l),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  private loadChildren(kind: string, parent: string | undefined, target: ReturnType<typeof signal<EntryList | null>>) {
    target.set(null);
    if (!parent) return;
    this.api.list(kind, undefined, parent).subscribe({
      next: (l) => target.set(l),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected choose(kind: Column['kind'], e: EntrySummary): void {
    if (kind === 'agency') {
      this.router.navigate([], { queryParams: { agency: e.id } });
    } else if (kind === 'agreement') {
      this.router.navigate([], { queryParams: { agency: this.agency(), agreement: e.id } });
    } else {
      this.router.navigate(['/entry', 'book', e.id]);
    }
  }
}
