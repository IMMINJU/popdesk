/** Joins the ids of the texts that describe a control; `undefined` when there are none. */
export function describedBy(...ids: (string | false | undefined)[]): string | undefined {
  const joined = ids.filter(Boolean).join(' ');
  return joined === '' ? undefined : joined;
}

/**
 * `aria-describedby` of a button in the `actions` of a `CommentItem`: the author and the written time of the comment
 * drawn with this `id` (like the file name on an attachment's 지우기).
 */
export function commentActionDescribedBy(id: string): string {
  return `${id}-author ${id}-time`;
}
