import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Api, KindDef, Me } from './api';

/** The signed-in user and the entry kinds, loaded once at start. */
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly api = inject(Api);

  readonly me = signal<Me | null>(null);
  readonly kinds = signal<KindDef[]>([]);
  readonly canEdit = computed(() => this.me()?.permissions.includes('edit-entries') ?? false);
  readonly canQuery = computed(() => this.me()?.permissions.includes('run-queries') ?? false);

  async load(): Promise<void> {
    const [me, kinds] = await Promise.all([firstValueFrom(this.api.me()), firstValueFrom(this.api.kinds())]);
    this.me.set(me);
    this.kinds.set(kinds);
  }

  kind(id: string | null | undefined): KindDef | undefined {
    return this.kinds().find((k) => k.id === id);
  }

  /** The name of that place, for breadcrumbs. */
  listLabel(kind: KindDef): string {
    if (['agency', 'agreement', 'book'].includes(kind.id)) return 'Agencies & books';
    if (kind.id === 'permission') return 'Features';
    return kind.plural;
  }

  /** Where an entry of this kind lives in the app's navigation. */
  listLink(kind: KindDef | undefined): string[] {
    if (!kind) return ['/'];
    if (kind.group === 'People') return ['/people', kind.id];
    if (['agency', 'agreement', 'book'].includes(kind.id)) return ['/accounts', 'agencies'];
    if (kind.group === 'Accounts') return ['/accounts', kind.id];
    if (kind.id === 'permission') return ['/reference', 'feature'];
    return ['/reference', kind.id];
  }
}
