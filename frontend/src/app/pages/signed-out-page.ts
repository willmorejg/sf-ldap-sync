import { Component, computed, input } from '@angular/core';

const REASONS: Record<string, string> = {
  forbidden: 'Your account does not have access to InsureDirectory. Ask an administrator for the “directory” feature.',
  expired: 'The sign-in took too long or was started in another window. Please try again.',
  sign_in_failed: 'Signing in did not work. Please try again.',
  access_denied: 'Sign-in was cancelled.',
};

/** Shown after signing out, or when sign-in failed or was refused. Needs no session. */
@Component({
  selector: 'app-signed-out-page',
  template: `
    <main class="signed-out card">
      <h1>InsureDirectory</h1>
      <p>{{ message() }}</p>
      <a class="button primary" href="/auth/login?returnTo=%2F">Sign in</a>
    </main>
  `,
})
export class SignedOutPage {
  readonly reason = input<string>();
  protected readonly message = computed(() =>
    this.reason() ? (REASONS[this.reason()!] ?? 'You are not signed in.') : 'You have signed out.');
}
