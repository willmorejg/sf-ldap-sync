import { buildFilter, conditionFilter, escapeValue } from './query-page';

describe('query builder filters', () => {
  it('escapes RFC 4515 special characters', () => {
    expect(escapeValue('a*b(c)\\d')).toBe('a\\2ab\\28c\\29\\5cd');
  });

  it('builds single conditions', () => {
    expect(conditionFilter({ attribute: 'uid', op: '=', value: 'jdoe', not: false })).toBe('(uid=jdoe)');
    expect(conditionFilter({ attribute: 'cn', op: 'contains', value: 'Ann', not: false })).toBe('(cn=*Ann*)');
    expect(conditionFilter({ attribute: 'mail', op: 'present', value: '', not: true })).toBe('(!(mail=*))');
  });

  it('combines conditions and the object class', () => {
    const c = [
      { attribute: 'acmeUserType', op: '=' as const, value: 'agent', not: false },
      { attribute: 'sn', op: 'starts' as const, value: 'Sm', not: false },
      { attribute: '', op: '=' as const, value: 'ignored', not: false },
    ];
    expect(buildFilter(c, '&', 'inetOrgPerson')).toBe('(&(objectClass=inetOrgPerson)(acmeUserType=agent)(sn=Sm*))');
    expect(buildFilter([c[0]], '|', '')).toBe('(acmeUserType=agent)');
    expect(buildFilter([], '&', '')).toBe('(objectClass=*)');
  });
});
