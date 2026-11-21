import { Component, computed, effect, input, output, signal } from '@angular/core';
import { AttributeValues, Entry, FieldDef, KindDef } from '../core/api';
import { DnPicker } from './dn-picker';

/**
 * The form for any kind of entry, built from the kind's field list (Kinds.java).
 * On save it emits only what changed (an empty list removes an attribute); for a
 * new entry, everything filled in.
 */
@Component({
  selector: 'app-entry-form',
  imports: [DnPicker],
  template: `
    <form class="form-grid" (submit)="submit($event)" novalidate>
      @for (f of kind().fields; track f.attribute) {
        @if (f.type !== 'READONLY' || entry()) {
          <div class="field" [class.wide]="f.type === 'DN_LIST' || f.type === 'TEXT_LIST'">
            <label [for]="'f-' + f.attribute">
              {{ f.label }}@if (f.required) {<span class="req" aria-hidden="true">*</span>}
              @if (!canEdit(f)) {<span class="lock" [title]="lockReason(f)">🔒</span>}
            </label>
            @switch (f.type) {
              @case ('TEXT_LIST') {
                <textarea [id]="'f-' + f.attribute" rows="3" [disabled]="!canEdit(f)"
                          [value]="(values()[f.attribute] ?? []).join('\\n')"
                          (input)="setList(f, $any($event.target).value)"></textarea>
              }
              @case ('DN') {
                <app-dn-picker [inputId]="'f-' + f.attribute" [kinds]="f.dnKinds ?? []" [multiple]="false"
                               [labels]="entry()?.labels ?? {}" [disabled]="!canEdit(f)"
                               [values]="values()[f.attribute] ?? []" (valuesChange)="set(f, $event)" />
              }
              @case ('DN_LIST') {
                <app-dn-picker [inputId]="'f-' + f.attribute" [kinds]="f.dnKinds ?? []"
                               [labels]="entry()?.labels ?? {}" [disabled]="!canEdit(f)"
                               [values]="values()[f.attribute] ?? []" (valuesChange)="set(f, $event)" />
              }
              @case ('READONLY') {
                <output [id]="'f-' + f.attribute">{{ (values()[f.attribute] ?? []).join(', ') || '—' }}</output>
              }
              @default {
                <input [id]="'f-' + f.attribute" [disabled]="!canEdit(f)" [required]="f.required"
                       [value]="(values()[f.attribute] ?? [])[0] ?? ''"
                       (input)="setText(f, $any($event.target).value)">
              }
            }
            @if (f.help) {<small class="help">{{ f.help }}</small>}
          </div>
        }
      }
      @if (editableCount() > 0) {
        <div class="form-actions wide">
          <button type="submit" class="primary" [disabled]="busy() || (entry() && !dirty())">
            {{ entry() ? 'Save changes' : 'Create ' + kind().label.toLowerCase() }}
          </button>
          @if (entry() && dirty()) {
            <button type="button" (click)="reset()">Discard changes</button>
          }
        </div>
      }
    </form>
  `,
})
export class EntryForm {
  readonly kind = input.required<KindDef>();
  readonly entry = input<Entry | null>(null);
  readonly allowEdit = input(true);
  readonly busy = input(false);
  readonly save = output<AttributeValues>();

  protected readonly values = signal<AttributeValues>({});

  protected readonly editableCount = computed(() => this.kind().fields.filter((f) => this.canEdit(f)).length);
  protected readonly dirty = computed(() => Object.keys(this.changes()).length > 0);
  private readonly changes = computed(() => {
    const out: AttributeValues = {};
    const before = this.entry()?.attributes ?? {};
    for (const f of this.kind().fields) {
      if (!this.canEdit(f)) continue;
      const now = clean(this.values()[f.attribute] ?? []);
      const was = before[f.attribute] ?? [];
      if (!same(now, was)) out[f.attribute] = now;
    }
    return out;
  });

  constructor() {
    effect(() => {
      this.entry();
      this.reset();
    });
  }

  protected canEdit(f: FieldDef): boolean {
    if (!this.allowEdit() || f.type === 'READONLY') return false;
    const e = this.entry();
    if (!e) return true;
    if (f.attribute.toLowerCase() === this.kind().rdnAttribute.toLowerCase()) return false;
    return e.editable.some((a) => a.toLowerCase() === f.attribute.toLowerCase());
  }

  protected lockReason(f: FieldDef): string {
    const e = this.entry();
    if (!this.allowEdit()) return 'You can view but not change entries';
    if (f.type === 'READONLY') return 'Set by the synchronisation';
    if (e && f.attribute.toLowerCase() === this.kind().rdnAttribute.toLowerCase()) return 'Cannot be changed';
    return e?.managedBy ? `Maintained by ${e.managedBy}` : 'Read only';
  }

  protected set(f: FieldDef, v: string[]): void {
    this.values.update((m) => ({ ...m, [f.attribute]: v }));
  }

  protected setText(f: FieldDef, v: string): void {
    this.set(f, v.trim() ? [v] : []);
  }

  protected setList(f: FieldDef, v: string): void {
    this.set(f, v.split('\n'));
  }

  protected reset(): void {
    const e = this.entry();
    const v: AttributeValues = {};
    for (const f of this.kind().fields) {
      v[f.attribute] = [...(e?.attributes[f.attribute] ?? [])];
    }
    this.values.set(v);
  }

  protected submit(ev: Event): void {
    ev.preventDefault();
    if (this.entry()) {
      this.save.emit(this.changes());
      return;
    }
    const all: AttributeValues = {};
    for (const f of this.kind().fields) {
      const v = clean(this.values()[f.attribute] ?? []);
      if (v.length && f.type !== 'READONLY') all[f.attribute] = v;
    }
    this.save.emit(all);
  }
}

function clean(v: string[]): string[] {
  return v.map((x) => x.trim()).filter((x) => x.length > 0);
}

function same(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  const s = new Set(b.map((x) => x.toLowerCase()));
  return a.every((x) => s.has(x.toLowerCase()));
}
