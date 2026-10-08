export interface AvatarProps {
  /** Person name, used for initials and, when not decorative, the text alternative. */
  name: string;
  /**
   * `person` shows the photo or initials. `support` is the 지원팀 mark the
   * portal shows for agents instead of their photo and name (D-49).
   * Default `person`.
   */
  kind?: 'person' | 'support';
  /** Photo URL. Photos come in slice 1 (ACCT-8, D-52); the first slice uses initials. */
  photoUrl?: string;
  /** 작게, 보통. Default `md`. */
  size?: 'sm' | 'md';
  /** True when the name is written next to it, so it is not read twice (`alt=""`). */
  decorative?: boolean;
}

export function Avatar(props: AvatarProps) {
  const initial = props.name.trim().slice(0, 1);
  return (
    <span
      data-pd="Avatar"
      data-kind={props.kind ?? 'person'}
      data-size={props.size ?? 'md'}
      role={props.decorative ? undefined : 'img'}
      aria-label={props.decorative ? undefined : props.name}
      aria-hidden={props.decorative}
    >
      {props.kind === 'support' ? null : initial}
    </span>
  );
}
