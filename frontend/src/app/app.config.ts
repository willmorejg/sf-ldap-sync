import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { apiInterceptor } from './core/interceptor';
import { Session } from './core/session';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([apiInterceptor])),
    // Loads the user and the entry kinds before the first page; without a session
    // the interceptor sends the browser to sign in (except on /signed-out).
    provideAppInitializer(() => {
      if (location.pathname.startsWith('/signed-out')) return Promise.resolve();
      return inject(Session).load().catch(() => undefined);
    }),
  ],
};
