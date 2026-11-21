import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, QueryRequest, QueryResult, SavedQuery, SchemaInfo, errorMessage } from '../core/api';
import { QuerySpec, jndiCode, unboundIdCode } from './query-code';

type Op = '=' | '~=' | '>=' | '<=' | 'present' | 'contains' | 'starts' | 'ends';

interface Condition {
  attribute: string;
  op: Op;
  value: string;
  not: boolean;
}

const OPS: { id: Op; label: string }[] = [
  { id: '=', label: 'equals' },
  { id: 'contains', label: 'contains' },
  { id: 'starts', label: 'starts with' },
  { id: 'ends', label: 'ends with' },
  { id: 'present', label: 'is present' },
  { id: '>=', label: '≥' },
  { id: '<=', label: '≤' },
  { id: '~=', label: 'sounds like' },
];

/** RFC 4515 escaping of a filter value. */
export function escapeValue(v: string): string {
  return v.replace(/[\\*()\0]/g, (c) => '\\' + c.charCodeAt(0).toString(16).padStart(2, '0'));
}

export function conditionFilter(c: Condition): string {
  const v = escapeValue(c.value);
  const f =
    c.op === 'present' ? `(${c.attribute}=*)`
    : c.op === 'contains' ? `(${c.attribute}=*${v}*)`
    : c.op === 'starts' ? `(${c.attribute}=${v}*)`
    : c.op === 'ends' ? `(${c.attribute}=*${v})`
    : `(${c.attribute}${c.op}${v})`;
  return c.not ? `(!${f})` : f;
}

export function buildFilter(conditions: Condition[], join: '&' | '|', objectClass: string): string {
  const parts = conditions.filter((c) => c.attribute && (c.op === 'present' || c.value)).map(conditionFilter);
  if (objectClass) parts.unshift(`(objectClass=${objectClass})`);
  if (!parts.length) return '(objectClass=*)';
  return parts.length === 1 ? parts[0] : `(${join}${parts.join('')})`;
}

/** Shell-quotes for the ldapsearch command line. */
function sq(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`;
}

/** Builds LDAP filters, shows the matching ldapsearch command and LDAP URL, and runs them. */
@Component({
  selector: 'app-query-page',
  imports: [FormsModule],
  template: `
    <header class="page-head">
      <div>
        <p class="eyebrow">Developer</p>
        <h1>Query builder</h1>
        <p class="muted">Build a search with conditions or write the filter yourself; copy it as an ldapsearch command,
          an LDAP URL, or Java code another application can run. Searches here run as svc-directory and never return
          passwords.</p>
      </div>
    </header>

    <div class="query-layout">
      <section class="card" aria-labelledby="build-h">
        <h2 id="build-h">Search</h2>
        <div class="form-grid">
          <div class="field wide">
            <label for="base">Base DN</label>
            <input id="base" list="bases" [(ngModel)]="baseDn" spellcheck="false">
            <datalist id="bases">
              @for (b of bases(); track b) {<option [value]="b"></option>}
            </datalist>
          </div>
          <div class="field">
            <label for="scope">Scope</label>
            <select id="scope" [(ngModel)]="scope">
              <option value="sub">Whole subtree (sub)</option>
              <option value="one">One level (one)</option>
              <option value="base">The base entry only (base)</option>
            </select>
          </div>
          <div class="field">
            <label for="oc">Object class</label>
            <input id="oc" list="ocs" [(ngModel)]="objectClass" (ngModelChange)="touchBuilder()" placeholder="any">
            <datalist id="ocs">
              @for (o of schema()?.objectClasses ?? []; track o) {<option [value]="o"></option>}
            </datalist>
          </div>
        </div>

        <fieldset class="conditions">
          <legend>Conditions
            <select [(ngModel)]="join" (ngModelChange)="touchBuilder()" aria-label="How conditions combine">
              <option value="&">all must match (AND)</option>
              <option value="|">any may match (OR)</option>
            </select>
          </legend>
          @for (c of conditions(); track $index; let i = $index) {
            <div class="condition">
              <label class="inline"><input type="checkbox" [(ngModel)]="c.not" (ngModelChange)="touchBuilder()"> not</label>
              <input list="attrs" [(ngModel)]="c.attribute" (ngModelChange)="touchBuilder()" placeholder="attribute"
                     [attr.aria-label]="'Attribute of condition ' + (i + 1)" spellcheck="false">
              <select [(ngModel)]="c.op" (ngModelChange)="touchBuilder()" [attr.aria-label]="'Operator of condition ' + (i + 1)">
                @for (o of ops; track o.id) {<option [value]="o.id">{{ o.label }}</option>}
              </select>
              <input [(ngModel)]="c.value" (ngModelChange)="touchBuilder()" [disabled]="c.op === 'present'"
                     placeholder="value" [attr.aria-label]="'Value of condition ' + (i + 1)">
              <button type="button" class="icon" (click)="removeCondition(i)" [attr.aria-label]="'Remove condition ' + (i + 1)">×</button>
            </div>
          }
          <button type="button" class="small" (click)="addCondition()">Add condition</button>
          <datalist id="attrs">
            @for (a of schema()?.attributes ?? []; track a.name) {<option [value]="a.name">{{ a.description }}</option>}
          </datalist>
        </fieldset>

        <div class="form-grid">
          <div class="field wide">
            <label for="filter">Filter <small class="muted">(edit freely; the conditions above rewrite it)</small></label>
            <textarea id="filter" rows="2" class="mono" [(ngModel)]="filter" spellcheck="false"></textarea>
          </div>
          <div class="field">
            <label for="attrsOut">Return attributes</label>
            <input id="attrsOut" [(ngModel)]="returnAttrs" placeholder="all (comma separated)" spellcheck="false">
          </div>
          <div class="field">
            <label for="limit">Size limit</label>
            <input id="limit" type="number" min="1" max="500" [(ngModel)]="sizeLimit">
          </div>
        </div>
        <div class="form-actions">
          <button type="button" class="primary" (click)="run()" [disabled]="busy()">Run search</button>
          <button type="button" (click)="save()" [disabled]="busy()">Save…</button>
        </div>
      </section>

      <aside class="side">
        <section class="card" aria-labelledby="copy-h">
          <h2 id="copy-h">Copy</h2>
          <p class="small muted">ldapsearch</p>
          <pre class="code">{{ command() }}</pre>
          <button type="button" class="small" (click)="copy(command())">Copy command</button>
          <p class="small muted">LDAP URL (RFC 4516)</p>
          <pre class="code">{{ ldapUrl() }}</pre>
          <button type="button" class="small" (click)="copy(ldapUrl())">Copy URL</button>
        </section>
        <section class="card" aria-labelledby="saved-h">
          <h2 id="saved-h">Saved queries</h2>
          @if (saved().length) {
            <ul class="link-list">
              @for (s of saved(); track s.id) {
                <li>
                  <button type="button" class="link" (click)="useSaved(s)" [title]="s.filter">{{ s.name }}</button>
                  <small class="muted"> by {{ s.createdBy }}</small>
                  <button type="button" class="icon" (click)="deleteSaved(s)" [attr.aria-label]="'Delete ' + s.name">×</button>
                </li>
              }
            </ul>
          } @else {
            <p class="muted small">None yet.</p>
          }
        </section>
      </aside>
    </div>

    <section class="card" aria-labelledby="java-h">
      <div class="section-head">
        <h2 id="java-h">Java</h2>
        <div class="segmented" role="group" aria-label="Java library">
          <button type="button" class="small" [attr.aria-pressed]="javaFlavour() === 'unboundid'"
                  (click)="javaFlavour.set('unboundid')">UnboundID LDAP SDK</button>
          <button type="button" class="small" [attr.aria-pressed]="javaFlavour() === 'jndi'"
                  (click)="javaFlavour.set('jndi')">JNDI (no dependency)</button>
        </div>
      </div>
      <p class="small muted">
        @if (javaFlavour() === 'unboundid') {
          A class with this search, paged so it also returns more than the server's page limit. Call
          <code>LdapQuery.search(pool)</code> from your code, or run <code>main</code>.
        } @else {
          The same search with the JDK's own LDAP client. It does not page, so the server's limit for the bind
          account applies.
        }
        The connection comes from <code>LDAP_URL</code>, <code>LDAP_BIND_DN</code> and <code>LDAP_BIND_PASSWORD</code>;
        bind as your application's own service account, never svc-directory.
      </p>
      <pre class="code java">{{ javaCode() }}</pre>
      <button type="button" class="small" (click)="copy(javaCode())">Copy Java</button>
    </section>

    @if (error()) {<p class="alert error" role="alert">{{ error() }}</p>}
    @if (result(); as r) {
      <section class="card" aria-labelledby="res-h">
        <h2 id="res-h">{{ r.count }} {{ r.count === 1 ? 'entry' : 'entries' }}
          <small class="muted">in {{ r.millis }} ms{{ r.truncated ? ' · stopped at the size limit (' + r.sizeLimit + ')' : '' }}</small></h2>
        @for (e of r.entries; track e.dn) {
          <details class="result">
            <summary class="mono">{{ e.dn }}</summary>
            <dl class="facts small">
              @for (a of attrs(e.attributes); track a[0]) {
                <dt>{{ a[0] }}</dt><dd>@for (v of a[1]; track $index) {<div class="mono">{{ v }}</div>}</dd>
              }
            </dl>
          </details>
        }
      </section>
    }
  `,
})
export class QueryPage {
  private readonly api = inject(Api);

  protected readonly ops = OPS;
  protected readonly schema = signal<SchemaInfo | null>(null);
  protected readonly saved = signal<SavedQuery[]>([]);
  protected readonly result = signal<QueryResult | null>(null);
  protected readonly error = signal('');
  protected readonly busy = signal(false);
  protected readonly conditions = signal<Condition[]>([{ attribute: 'acmeUserType', op: '=', value: 'agent', not: false }]);
  protected readonly baseDn = signal('dc=acmeinsurance,dc=com');
  protected readonly scope = signal<QueryRequest['scope']>('sub');
  protected readonly objectClass = signal('');
  protected readonly join = signal<'&' | '|'>('&');
  protected readonly filter = signal('(acmeUserType=agent)');
  protected readonly returnAttrs = signal('');
  protected readonly sizeLimit = signal(100);
  private readonly attrList = computed(() =>
    this.returnAttrs().split(/[,\s]+/).map((a) => a.trim()).filter(Boolean));

  protected readonly bases = computed(() => {
    const b = this.schema()?.baseDn ?? 'dc=acmeinsurance,dc=com';
    return [b, `ou=people,${b}`, `ou=agents,ou=people,${b}`, `ou=members,ou=people,${b}`,
      `ou=internal,ou=people,${b}`, `ou=other,ou=people,${b}`, `ou=agencies,ou=accounts,${b}`,
      `ou=clients,ou=accounts,${b}`, `ou=underwritingCompanies,${b}`, `ou=features,${b}`];
  });

  protected readonly command = computed(() => {
    const attrs = this.attrList().join(' ');
    return `ldapsearch -x -H ldap://localhost:1389 -D 'cn=…,ou=services,${this.schema()?.baseDn ?? 'dc=acmeinsurance,dc=com'}' -W \\\n`
      + `  -b ${sq(this.baseDn())} -s ${this.scope()} -z ${this.sizeLimit()} \\\n  ${sq(this.filter())}${attrs ? ' ' + attrs : ''}`;
  });

  protected readonly javaFlavour = signal<'unboundid' | 'jndi'>('unboundid');
  protected readonly javaCode = computed(() => {
    const spec: QuerySpec = { baseDn: this.baseDn().trim(), scope: this.scope(), filter: this.filter().trim(),
      attributes: this.attrList(), sizeLimit: this.sizeLimit(), url: 'ldap://localhost:1389' };
    return this.javaFlavour() === 'unboundid' ? unboundIdCode(spec) : jndiCode(spec);
  });

  protected readonly ldapUrl = computed(() => {
    const enc = (s: string) => encodeURIComponent(s).replace(/%2C/gi, ',').replace(/%3D/gi, '=');
    return `ldap://localhost:1389/${enc(this.baseDn())}?${this.attrList().join(',')}?${this.scope()}?${enc(this.filter())}`;
  });

  constructor() {
    this.api.schema().subscribe({ next: (s) => this.schema.set(s), error: (e) => this.error.set(errorMessage(e)) });
    this.loadSaved();
  }

  protected touchBuilder(): void {
    this.filter.set(buildFilter(this.conditions(), this.join(), this.objectClass().trim()));
  }

  protected addCondition(): void {
    this.conditions.update((c) => [...c, { attribute: '', op: '=', value: '', not: false }]);
  }

  protected removeCondition(i: number): void {
    this.conditions.update((c) => c.filter((_, j) => j !== i));
    this.touchBuilder();
  }

  private request(): QueryRequest {
    return { baseDn: this.baseDn().trim(), scope: this.scope(), filter: this.filter().trim(),
      attributes: this.attrList(), sizeLimit: this.sizeLimit() };
  }

  protected run(): void {
    this.busy.set(true);
    this.error.set('');
    this.api.runQuery(this.request()).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.result.set(r);
      },
      error: (e) => {
        this.busy.set(false);
        this.result.set(null);
        this.error.set(errorMessage(e));
      },
    });
  }

  protected save(): void {
    const name = prompt('Name for this query');
    if (!name?.trim()) return;
    this.api.saveQuery({ ...this.request(), name: name.trim() }).subscribe({
      next: () => this.loadSaved(),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected useSaved(s: SavedQuery): void {
    this.baseDn.set(s.baseDn);
    this.scope.set(s.scope.toLowerCase() as QueryRequest['scope']);
    this.filter.set(s.filter);
    this.returnAttrs.set(s.attributes ?? '');
  }

  protected deleteSaved(s: SavedQuery): void {
    if (!confirm(`Delete the saved query “${s.name}”?`)) return;
    this.api.deleteSavedQuery(s.id).subscribe({ next: () => this.loadSaved(), error: (e) => this.error.set(errorMessage(e)) });
  }

  private loadSaved(): void {
    this.api.savedQueries().subscribe({ next: (s) => this.saved.set(s), error: () => this.saved.set([]) });
  }

  protected attrs(a: Record<string, string[]>): [string, string[]][] {
    return Object.entries(a);
  }

  protected copy(text: string): void {
    navigator.clipboard?.writeText(text);
  }
}
