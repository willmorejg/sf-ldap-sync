import { QuerySpec, javaString, jndiCode, unboundIdCode } from './query-code';

const spec: QuerySpec = {
  baseDn: 'ou=agents,ou=people,dc=acmeinsurance,dc=com',
  scope: 'one',
  filter: '(&(acmeUserType=agent)(cn=*O\\5cBrien "Jr"*))',
  attributes: ['uid', 'mail'],
  sizeLimit: 50,
  url: 'ldap://localhost:1389',
};

describe('query builder Java code', () => {
  it('escapes Java string literals', () => {
    expect(javaString('a"b\\c\nd')).toBe('"a\\"b\\\\c\\nd"');
  });

  it('writes the search into the UnboundID class', () => {
    const code = unboundIdCode(spec);
    expect(code).toContain('static final String BASE_DN = "ou=agents,ou=people,dc=acmeinsurance,dc=com";');
    expect(code).toContain('static final SearchScope SCOPE = SearchScope.ONE;');
    expect(code).toContain('static final String FILTER = "(&(acmeUserType=agent)(cn=*O\\\\5cBrien \\"Jr\\"*))";');
    expect(code).toContain('static final String[] ATTRIBUTES = {"uid", "mail"};');
    expect(code).toContain('static final int SIZE_LIMIT = 50;');
    expect(code).toContain('SimplePagedResultsControl');
    expect(code).toContain('ResultCode.SIZE_LIMIT_EXCEEDED'); // UnboundID throws when the limit is reached
    expect(code).not.toContain('svc-directory');
  });

  it('writes the search into the JNDI class', () => {
    const code = jndiCode({ ...spec, scope: 'sub', attributes: [] });
    expect(code).toContain('controls.setSearchScope(SearchControls.SUBTREE_SCOPE);');
    expect(code).toContain('static final String[] ATTRIBUTES = null;');
    expect(code).toContain('static final long SIZE_LIMIT = 50;');
  });
});
