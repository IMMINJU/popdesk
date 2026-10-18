// The typed check of the 익명화 dialog (10a design.md 0절 10, S10A-AC-30): the admin
// types the person's email, so a slip onto the next row does not anonymize it.

/**
 * Takes what was typed and the account's stored email. Gives true when they are the
 * same after trimming spaces at both ends of what was typed, ignoring capitals on
 * both sides; a space inside, or one character more or less, gives false.
 */
export function matchesEmail(typed: string, email: string): boolean {
  return typed.trim().toLowerCase() === email.toLowerCase();
}
