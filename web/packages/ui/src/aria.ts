/** Joins the ids of the texts that describe a control; `undefined` when there are none. */
export function describedBy(...ids: (string | false | undefined)[]): string | undefined {
  const joined = ids.filter(Boolean).join(' ');
  return joined === '' ? undefined : joined;
}
