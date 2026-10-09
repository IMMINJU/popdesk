import { ChatIcon } from '../icons';

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

/** Circle with initials or the 지원팀 mark (tokens.md 9절). */
export function Avatar(props: AvatarProps) {
  const kind = props.kind ?? 'person';
  const initial = Array.from(props.name.trim())[0] ?? '';
  const a11y = props.decorative ? { 'aria-hidden': true as const } : { role: 'img', 'aria-label': props.name };
  return (
    <span className="pd-avatar" data-pd="Avatar" data-kind={kind} data-size={props.size ?? 'md'} {...a11y}>
      {kind === 'support' ? (
        <ChatIcon size={props.size === 'sm' ? 12 : 14} />
      ) : props.photoUrl ? (
        <img src={props.photoUrl} alt="" />
      ) : (
        <span className="pd-avatar-initial">{initial}</span>
      )}
    </span>
  );
}
