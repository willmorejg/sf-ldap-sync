import { Component, inject, signal } from '@angular/core';
import { Api, AuditRecord, errorMessage } from '../core/api';

/** Changes made through InsureDirectory, newest first. Attribute names only, never values. */
@Component({
  selector: 'app-audit-page',
  template: `
    <header class="page-head">
      <div>
        <p class="eyebrow">Audit</p>
        <h1>Recent changes</h1>
        <p class="muted">Every change made here, newest first. Values are not recorded, only which attributes changed.
          Changes made by the synchronisation or by hand in the directory are not listed.</p>
      </div>
    </header>
    <form class="toolbar" role="search" (submit)="$event.preventDefault(); load()">
      <label class="sr-only" for="aq">Filter</label>
      <input id="aq" type="search" placeholder="Filter by person, entry or action" [value]="q()"
             (input)="q.set($any($event.target).value)">
      <button type="submit">Filter</button>
    </form>
    @if (error()) {<p class="alert error" role="alert">{{ error() }}</p>}
    <div class="table-wrap">
      <table>
        <thead><tr><th scope="col">When</th><th scope="col">Who</th><th scope="col">Action</th>
          <th scope="col">Entry</th><th scope="col">Details</th></tr></thead>
        <tbody>
          @for (r of records(); track r.id) {
            <tr>
              <td class="nowrap">{{ when(r.at) }}</td>
              <td>{{ r.actor }}</td>
              <td><span class="badge" [class.danger]="r.action === 'delete'">{{ r.action }}</span></td>
              <td class="mono small">{{ r.dn }}</td>
              <td class="small">{{ r.details }}</td>
            </tr>
          } @empty {
            <tr><td colspan="5" class="muted">Nothing recorded{{ q() ? ' that matches' : ' yet' }}.</td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class AuditPage {
  private readonly api = inject(Api);
  protected readonly q = signal('');
  protected readonly records = signal<AuditRecord[]>([]);
  protected readonly error = signal('');

  constructor() {
    this.load();
  }

  protected load(): void {
    this.api.audit(this.q()).subscribe({
      next: (r) => this.records.set(r),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected when(iso: string): string {
    return new Date(iso).toLocaleString();
  }
}
