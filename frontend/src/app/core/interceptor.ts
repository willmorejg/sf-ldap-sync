import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';

/** Header the API requires on every request other than GET (see Guard.java). */
export const REQUESTED_WITH = { name: 'X-Requested-With', value: 'InsureDirectory' };

/** Where the browser goes to sign in, coming back to the current page. */
export function signInUrl(): string {
  const here = location.pathname + location.search;
  return '/auth/login?returnTo=' + encodeURIComponent(here);
}

/**
 * Adds X-Requested-With, and sends the browser to sign in when the session has
 * ended (401). The session itself is an HttpOnly cookie the app never sees.
 */
export const apiInterceptor: HttpInterceptorFn = (req, next) => {
  const r = req.method === 'GET' ? req : req.clone({ setHeaders: { [REQUESTED_WITH.name]: REQUESTED_WITH.value } });
  return next(r).pipe(
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401 && req.url.startsWith('/api/')) {
        location.assign(signInUrl());
      }
      return throwError(() => err);
    }),
  );
};
