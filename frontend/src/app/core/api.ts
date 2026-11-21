import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Field types, as KindDef fields declare them (FieldType.java). */
export type FieldType = 'TEXT' | 'TEXT_LIST' | 'DN' | 'DN_LIST' | 'READONLY';

export interface FieldDef {
  attribute: string;
  label: string;
  type: FieldType;
  required: boolean;
  dnKinds: string[] | null;
  help: string | null;
}

export interface KindDef {
  id: string;
  label: string;
  plural: string;
  group: string;
  description: string;
  structuralClass: string;
  objectClasses: string[];
  rdnAttribute: string;
  baseRdn: string | null;
  parentKind: string | null;
  childKind: string | null;
  sourceTracked: boolean;
  userType: string | null;
  passwordPolicy: string | null;
  fields: FieldDef[];
}

export interface Me {
  username: string;
  name: string;
  permissions: string[];
}

export interface EntrySummary {
  id: string;
  dn: string;
  rdn: string;
  cn: string | null;
  displayName: string | null;
  mail: string | null;
  description: string | null;
  managedBy: string | null;
  locked: boolean;
  deactivated: boolean;
  underwritingCompany: string | null;
}

export interface EntryList {
  kind: string;
  parentId: string | null;
  parentLabel: string | null;
  truncated: boolean;
  limit: number;
  items: EntrySummary[];
}

export interface PersonStatus {
  locked: boolean;
  lockedUntilUnlocked: boolean;
  passwordReset: boolean;
  deactivated: string | null;
  passwordChanged: string | null;
}

export interface Entry {
  id: string;
  dn: string;
  kind: string;
  rdn: string;
  label: string;
  parentId?: string;
  parentLabel?: string;
  managedBy: string | null;
  editable: string[];
  deletable: boolean;
  attributes: Record<string, string[]>;
  labels: Record<string, string>;
  status?: PersonStatus;
  meta: { created: string | null; modified: string | null; modifiedBy: string | null };
}

export interface LookupItem {
  id: string;
  dn: string;
  kind: string;
  kindLabel: string;
  label: string;
  displayName: string | null;
}

export interface QueryRequest {
  baseDn: string;
  scope: 'base' | 'one' | 'sub';
  filter: string;
  attributes: string[];
  sizeLimit?: number;
}

export interface QueryResult {
  filter: string;
  count: number;
  truncated: boolean;
  sizeLimit: number;
  millis: number;
  entries: { dn: string; attributes: Record<string, string[]> }[];
}

export interface SchemaInfo {
  baseDn: string;
  attributes: { name: string; description: string | null; dn: boolean; singleValued: boolean }[];
  objectClasses: string[];
}

export interface SavedQuery {
  id: number;
  name: string;
  description: string | null;
  baseDn: string;
  scope: string;
  filter: string;
  attributes: string;
  createdBy: string;
  createdAt: string;
}

export interface AuditRecord {
  id: number;
  at: string;
  actor: string;
  action: string;
  kind: string;
  dn: string;
  details: string | null;
}

/** Attribute values to write: an empty list removes the attribute. */
export type AttributeValues = Record<string, string[]>;

/** The InsureDirectory API (backend/, routes/directory-api.camel.yaml). */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);

  me(): Observable<Me> {
    return this.http.get<Me>('/api/me');
  }

  kinds(): Observable<KindDef[]> {
    return this.http.get<KindDef[]>('/api/kinds');
  }

  list(kind: string, q?: string, parent?: string | null): Observable<EntryList> {
    let params = new HttpParams();
    if (q) params = params.set('q', q);
    if (parent) params = params.set('parent', parent);
    return this.http.get<EntryList>(`/api/entries/${kind}`, { params });
  }

  get(kind: string, id: string): Observable<Entry> {
    return this.http.get<Entry>(`/api/entries/${kind}/${id}`);
  }

  create(kind: string, attributes: AttributeValues, parentId?: string | null): Observable<Entry> {
    return this.http.post<Entry>(`/api/entries/${kind}`, { attributes, parentId: parentId ?? null });
  }

  update(kind: string, id: string, attributes: AttributeValues): Observable<Entry> {
    return this.http.put<Entry>(`/api/entries/${kind}/${id}`, { attributes });
  }

  delete(kind: string, id: string): Observable<{ deleted: boolean }> {
    return this.http.delete<{ deleted: boolean }>(`/api/entries/${kind}/${id}`);
  }

  lock(kind: string, id: string): Observable<Entry> {
    return this.http.post<Entry>(`/api/entries/${kind}/${id}/lock`, null);
  }

  unlock(kind: string, id: string): Observable<Entry> {
    return this.http.post<Entry>(`/api/entries/${kind}/${id}/unlock`, null);
  }

  resetPassword(kind: string, id: string): Observable<{ temporaryPassword: string; entry: Entry }> {
    return this.http.post<{ temporaryPassword: string; entry: Entry }>(
      `/api/entries/${kind}/${id}/password-reset`, null);
  }

  lookup(kinds: string[], q: string): Observable<LookupItem[]> {
    return this.http.get<LookupItem[]>('/api/lookup', { params: { kinds: kinds.join(','), q } });
  }

  runQuery(req: QueryRequest): Observable<QueryResult> {
    return this.http.post<QueryResult>('/api/query/run', req);
  }

  schema(): Observable<SchemaInfo> {
    return this.http.get<SchemaInfo>('/api/query/schema');
  }

  savedQueries(): Observable<SavedQuery[]> {
    return this.http.get<SavedQuery[]>('/api/query/saved');
  }

  saveQuery(q: QueryRequest & { name: string; description?: string }): Observable<SavedQuery> {
    return this.http.post<SavedQuery>('/api/query/saved', q);
  }

  deleteSavedQuery(id: number): Observable<unknown> {
    return this.http.delete(`/api/query/saved/${id}`);
  }

  audit(q?: string): Observable<AuditRecord[]> {
    return this.http.get<AuditRecord[]>('/api/audit', { params: q ? { q } : {} });
  }

  logout(): Observable<unknown> {
    return this.http.post('/auth/logout', null);
  }
}

/** The message of an API error ({"error", "message"}), or a generic one. */
export function errorMessage(err: unknown): string {
  const e = err as { error?: { message?: string }; status?: number; message?: string };
  if (e?.error?.message) return e.error.message;
  if (e?.status === 0) return 'The server cannot be reached.';
  return e?.message ?? 'Something went wrong.';
}
