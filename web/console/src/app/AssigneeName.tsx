// 담당자 as C-3 and C-4 show it (3-issues screens.md 2절).
import { Avatar, Badge } from '@popdesk/ui';
import type { Schemas } from '../api';
import { photoUrl } from './photos';

type Person = Schemas['Person'];

/** 담당자: photo and name, 담당 없음, and 볼 수 없음 after the name of one who cannot see the issue (S3-AC-78). */
export function AssigneeName(props: { person: Person | null; unavailable: boolean }) {
  if (!props.person) return <span className="pd-muted">담당 없음</span>;
  return (
    <span className="pd-person">
      <Avatar name={props.person.name} photoUrl={photoUrl(props.person.accountId, props.person.photoUpdatedAt)} size="sm" decorative />
      <span>{props.person.name}</span>
      {props.unavailable && <Badge tone="warning">볼 수 없음</Badge>}
    </span>
  );
}
