import { Component, computed, inject, input, model, signal } from '@angular/core';
import { Subject, debounceTime, distinctUntilChanged, switchMap, of, catchError } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Api, LookupItem } from '../core/api';

/**
 * Picks entries by name instead of typing DNs: shows the chosen entries as chips
 * and searches the given kinds as you type.
 */
@Component({
  selector: 'app-dn-picker',
  template: `
    <div class="picker" [class.disabled]="disabled()">
      @if (values().length) {
        <ul class="chips">
          @for (v of values(); track v) {
            <li class="chip" [title]="v">
              <span>{{ labelOf(v) }}</span>
              @if (!disabled()) {
                <button type="button" class="chip-x" (click)="remove(v)" [attr.aria-label]="'Remove ' + labelOf(v)">×</button>
              }
            </li>
          }
        </ul>
      } @else if (disabled()) {
        <span class="muted">None</span>
      }
      @if (!disabled() && (multiple() || !values().length)) {
        <input type="search" [id]="inputId()" [placeholder]="placeholder()" autocomplete="off"
               [value]="query()" (input)="search($any($event.target).value)" (keydown.escape)="close()">
        @if (results().length) {
          <ul class="results" role="listbox">
            @for (r of results(); track r.dn) {
              <li role="option">
                <button type="button" (click)="add(r)">
                  <span>{{ r.label }}</span>
                  <small class="muted">{{ r.kindLabel }}{{ r.displayName ? ' · ' + r.displayName : '' }}</small>
                </button>
              </li>
            }
          </ul>
        } @else if (query().length > 1 && !searching()) {
          <p class="muted small">Nothing found.</p>
        }
      }
    </div>
  `,
})
export class DnPicker {
  private readonly api = inject(Api);

  readonly values = model<string[]>([]);
  readonly kinds = input.required<string[]>();
  readonly labels = input<Record<string, string>>({});
  readonly multiple = input(true);
  readonly disabled = input(false);
  readonly inputId = input<string>('');

  protected readonly query = signal('');
  protected readonly results = signal<LookupItem[]>([]);
  protected readonly searching = signal(false);
  private readonly picked = signal<Record<string, string>>({});
  private readonly terms = new Subject<string>();
  protected readonly placeholder = computed(() => (this.multiple() ? 'Type to add…' : 'Type to choose…'));

  constructor() {
    this.terms.pipe(
      debounceTime(250),
      distinctUntilChanged(),
      switchMap((q) => {
        if (q.trim().length < 2) return of([] as LookupItem[]);
        return this.api.lookup(this.kinds(), q.trim()).pipe(catchError(() => of([] as LookupItem[])));
      }),
      takeUntilDestroyed(),
    ).subscribe((r) => {
      this.searching.set(false);
      this.results.set(r.filter((x) => !this.values().includes(x.dn)));
    });
  }

  protected labelOf(dn: string): string {
    return this.picked()[dn] ?? this.labels()[dn] ?? dn;
  }

  protected search(q: string): void {
    this.query.set(q);
    this.searching.set(q.trim().length > 1);
    this.terms.next(q);
  }

  protected add(r: LookupItem): void {
    this.picked.update((p) => ({ ...p, [r.dn]: r.label }));
    this.values.set(this.multiple() ? [...this.values(), r.dn] : [r.dn]);
    this.close();
  }

  protected remove(dn: string): void {
    this.values.set(this.values().filter((v) => v !== dn));
  }

  protected close(): void {
    this.query.set('');
    this.results.set([]);
    this.terms.next('');
  }
}
