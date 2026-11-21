import { Component, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';
import { Api } from './core/api';
import { Session } from './core/session';

/** Header, sidebar navigation and the page, laid out with CSS grid (styles.css: .shell). */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
})
export class App {
  protected readonly session = inject(Session);
  private readonly api = inject(Api);
  private readonly router = inject(Router);

  protected readonly navOpen = signal(false);
  private readonly url = toSignal(
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd), map((e) => e.urlAfterRedirects)),
    { initialValue: location.pathname },
  );
  protected readonly bare = computed(() => this.url().startsWith('/signed-out'));
  protected readonly people = computed(() => this.session.kinds().filter((k) => k.group === 'People'));
  protected readonly reference = computed(() =>
    this.session.kinds().filter((k) => k.group === 'Reference data' && k.id !== 'permission'));
  protected readonly otherAccounts = computed(() =>
    this.session.kinds().filter((k) => k.group === 'Accounts' && !['agency', 'agreement', 'book'].includes(k.id)));

  protected signOut(): void {
    this.api.logout().subscribe({ complete: () => location.assign('/signed-out') });
  }
}
