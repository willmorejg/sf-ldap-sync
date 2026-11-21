import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Api, AttributeValues, Entry, EntryList, errorMessage } from '../core/api';
import { Session } from '../core/session';
import { EntryForm } from '../shared/entry-form';

/** One entry: view and edit it, and for people lock, unlock or reset the password. Also "new". */
@Component({
  selector: 'app-entry-page',
  imports: [EntryForm, RouterLink],
  template: `
    @if (kindDef(); as k) {
      <nav class="crumbs" aria-label="Breadcrumb">
        <a [routerLink]="session.listLink(k)">{{ session.listLabel(k) }}</a>
        @if (entry()?.parentLabel || parentLabel()) {
          <span aria-hidden="true">›</span>
          <a [routerLink]="['/entry', k.parentKind, entry()?.parentId ?? parent()]">{{ entry()?.parentLabel ?? parentLabel() }}</a>
        }
      </nav>
      <header class="page-head">
        <div>
          <p class="eyebrow">{{ k.label }}</p>
          <h1>{{ isNew() ? 'New ' + k.label.toLowerCase() : (entry()?.attributes?.['cn']?.[0] ?? entry()?.rdn ?? '…') }}</h1>
          @if (entry(); as e) {
            <p class="dn" title="Distinguished name">{{ e.dn }}</p>
          }
        </div>
        @if (entry(); as e) {
          <div class="badges">
            @if (e.managedBy) {<span class="badge" title="Owned by this source">Maintained by {{ e.managedBy }}</span>}
            @else {<span class="badge ok">Hand-made</span>}
          </div>
        }
      </header>

      @if (notice()) {<p class="alert ok" role="status">{{ notice() }}</p>}
      @if (error()) {<p class="alert error" role="alert">{{ error() }}</p>}
      @if (password(); as pw) {
        <div class="alert ok password" role="status">
          <p>Temporary password (shown only now; they must change it at their next sign-in):</p>
          <code class="big">{{ pw }}</code>
          <button type="button" (click)="password.set('')">Done</button>
        </div>
      }

      <div class="entry-layout">
        <section class="card" aria-labelledby="details-h">
          <h2 id="details-h">Details</h2>
          @if (entry()?.managedBy) {
            <p class="muted small">
              Most details come from {{ entry()?.managedBy }}; change them there. Fields with 🔒 are read-only here.
            </p>
          }
          @if (isNew() || entry()) {
            <app-entry-form [kind]="k" [entry]="entry()" [allowEdit]="session.canEdit()" [busy]="busy()"
                            (save)="save($event)" />
          }
        </section>

        <aside class="side">
          @if (entry()?.status; as s) {
            <section class="card" aria-labelledby="access-h">
              <h2 id="access-h">Sign-in</h2>
              <dl class="facts">
                <dt>Status</dt>
                <dd>
                  @if (s.deactivated) {<span class="badge danger">Deactivated</span>}
                  @else if (s.locked) {<span class="badge warn">Locked</span>}
                  @else {<span class="badge ok">Active</span>}
                </dd>
                @if (s.passwordReset) {<dt>Password</dt><dd>Must be changed at next sign-in</dd>}
                @if (s.passwordChanged) {<dt>Last changed</dt><dd>{{ ldapTime(s.passwordChanged) }}</dd>}
              </dl>
              @if (session.canEdit()) {
                <div class="stack">
                  @if (s.locked) {
                    <button type="button" (click)="unlock()" [disabled]="busy() || !!s.deactivated"
                            [title]="s.deactivated ? 'Deactivated by its source; reactivate it there' : ''">Unlock</button>
                  } @else {
                    <button type="button" (click)="lock()" [disabled]="busy()">Lock</button>
                  }
                  <button type="button" (click)="reset()" [disabled]="busy()">Reset password</button>
                </div>
              }
            </section>
          }
          @if (entry(); as e) {
            <section class="card" aria-labelledby="meta-h">
              <h2 id="meta-h">History</h2>
              <dl class="facts">
                <dt>Created</dt><dd>{{ ldapTime(e.meta.created) }}</dd>
                <dt>Changed</dt><dd>{{ ldapTime(e.meta.modified) }}</dd>
                <dt>Changed by</dt><dd>{{ rdnValue(e.meta.modifiedBy) }}</dd>
              </dl>
            </section>
            @if (otherAttributes().length) {
              <section class="card" aria-labelledby="other-h">
                <h2 id="other-h">Other attributes</h2>
                <dl class="facts small">
                  @for (a of otherAttributes(); track a[0]) {
                    <dt>{{ a[0] }}</dt><dd>{{ a[1].join(', ') }}</dd>
                  }
                </dl>
              </section>
            }
            @if (session.canEdit() && e.deletable) {
              <section class="card danger-zone">
                <h2>Delete</h2>
                <p class="small muted">Removes the entry. Grants that point at it are removed too.</p>
                <button type="button" class="danger" (click)="remove()" [disabled]="busy()">Delete {{ k.label.toLowerCase() }}</button>
              </section>
            }
          }
        </aside>
      </div>

      @if (childKind() && entry(); as e) {
        <section class="card" aria-labelledby="children-h">
          <header class="section-head">
            <h2 id="children-h">{{ childKind()!.plural }}</h2>
            @if (session.canEdit()) {
              <a class="button" [routerLink]="['/entry', childKind()!.id, 'new']" [queryParams]="{ parent: e.id }">
                New {{ childKind()!.label.toLowerCase() }}</a>
            }
          </header>
          @if (children()?.items?.length) {
            <ul class="link-list">
              @for (c of children()!.items; track c.id) {
                <li><a [routerLink]="['/entry', childKind()!.id, c.id]">{{ c.rdn }}</a>
                  @if (c.displayName) {<span class="muted"> · {{ c.displayName }}</span>}</li>
              }
            </ul>
          } @else {
            <p class="muted">None yet.</p>
          }
        </section>
      }
    }
  `,
})
export class EntryPage {
  protected readonly session = inject(Session);
  private readonly api = inject(Api);
  private readonly router = inject(Router);

  readonly kind = input.required<string>();
  readonly id = input<string>();
  readonly parent = input<string>();

  protected readonly kindDef = computed(() => this.session.kind(this.kind()));
  protected readonly childKind = computed(() => this.session.kind(this.kindDef()?.childKind));
  protected readonly isNew = computed(() => !this.id() || this.id() === 'new');
  protected readonly entry = signal<Entry | null>(null);
  protected readonly children = signal<EntryList | null>(null);
  protected readonly parentLabel = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal('');
  protected readonly notice = signal('');
  protected readonly password = signal('');

  protected readonly otherAttributes = computed(() => {
    const e = this.entry();
    const k = this.kindDef();
    if (!e || !k) return [];
    const known = new Set(k.fields.map((f) => f.attribute.toLowerCase()));
    return Object.entries(e.attributes).filter(([a]) => !known.has(a.toLowerCase()));
  });

  constructor() {
    effect(() => {
      const kind = this.kind();
      const id = this.id();
      this.entry.set(null);
      this.children.set(null);
      this.error.set('');
      this.notice.set('');
      this.password.set('');
      if (id && id !== 'new') {
        this.api.get(kind, id).subscribe({ next: (e) => this.show(e), error: (e) => this.error.set(errorMessage(e)) });
      } else if (this.parent() && this.kindDef()?.parentKind) {
        this.api.get(this.kindDef()!.parentKind!, this.parent()!).subscribe({
          next: (p) => this.parentLabel.set(p.label),
          error: (e) => this.error.set(errorMessage(e)),
        });
      }
    });
  }

  private show(e: Entry): void {
    this.entry.set(e);
    const child = this.childKind();
    if (child) {
      this.api.list(child.id, undefined, e.id).subscribe((l) => this.children.set(l));
    }
  }

  protected save(values: AttributeValues): void {
    const k = this.kindDef()!;
    this.run(
      this.isNew() ? this.api.create(k.id, values, this.parent()) : this.api.update(k.id, this.id()!, values),
      (e) => {
        if (this.isNew()) {
          this.router.navigate(['/entry', k.id, e.id]);
        } else {
          this.show(e);
          this.notice.set('Saved.');
        }
      },
    );
  }

  protected lock(): void {
    this.run(this.api.lock(this.kind(), this.id()!), (e) => {
      this.show(e);
      this.notice.set('Locked: they cannot sign in until unlocked.');
    });
  }

  protected unlock(): void {
    this.run(this.api.unlock(this.kind(), this.id()!), (e) => {
      this.show(e);
      this.notice.set('Unlocked.');
    });
  }

  protected reset(): void {
    if (!confirm('Give this person a new temporary password? Their current password stops working.')) return;
    this.run(this.api.resetPassword(this.kind(), this.id()!), (r) => {
      this.show(r.entry);
      this.password.set(r.temporaryPassword);
    });
  }

  protected remove(): void {
    const k = this.kindDef()!;
    if (!confirm(`Delete ${this.entry()?.label}? This cannot be undone.`)) return;
    this.run(this.api.delete(k.id, this.id()!), () => {
      const p = this.entry()?.parentId;
      this.router.navigate(p && k.parentKind ? ['/entry', k.parentKind, p] : this.session.listLink(k));
    });
  }

  private run<T>(obs: import('rxjs').Observable<T>, done: (v: T) => void): void {
    this.busy.set(true);
    this.error.set('');
    this.notice.set('');
    obs.subscribe({
      next: (v) => {
        this.busy.set(false);
        done(v);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  /** 20261121071359Z → local date and time. */
  protected ldapTime(v: string | null): string {
    const m = v?.match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})/);
    if (!m) return v ?? '—';
    if (m[1] === '0000') return 'until unlocked';
    return new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6])).toLocaleString();
  }

  protected rdnValue(dn: string | null): string {
    return dn?.match(/^[^=]+=([^,]+)/)?.[1] ?? dn ?? '—';
  }
}
