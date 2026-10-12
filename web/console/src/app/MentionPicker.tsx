// Draws one "알릴 사람" (4-talk screens.md 2절 C-4) from the state of `useMentions`.
import { PeoplePicker } from '@popdesk/ui';
import { t, tf } from './locale';
import { MENTION_LIMIT, type Mentions, type Person } from './mentions';
import { TEXT } from './messages';

function roleWord(p: Person, reporterId: number): string {
  if (p.accountId === reporterId) return t('mentions.reporter');
  if (p.role === 'ADMIN') return t('mentions.admin');
  return p.role === 'AGENT' ? t('mentions.agent') : t('mentions.customer');
}

/** The folded line and, opened, the candidates as "이름 (역할)". No photos. */
export function MentionPicker(props: { id: string; mentions: Mentions }) {
  const { picked, open, candidates, notice, reporterId, setOpen, pick, retry } = props.mentions.view;
  return (
    <PeoplePicker
      id={props.id}
      label={t('mentions.label')}
      toggleLabel={t('mentions.toggle')}
      summary={picked.length === 0 ? t('mentions.none') : tf('mentions.summary', { names: picked.map((p) => p.name).join(', ') })}
      candidates={(candidates?.items ?? []).map((p) => ({ id: String(p.accountId), label: tf('mentions.line', { name: p.name, role: roleWord(p, reporterId) }) }))}
      value={picked.map((p) => String(p.accountId))}
      onChange={pick}
      open={open}
      onOpenChange={setOpen}
      max={MENTION_LIMIT}
      maxText={t('mentions.max')}
      status={candidates?.status ?? 'loading'}
      loadingText={t('mentions.loading')}
      failedText={t('mentions.failed')}
      retryLabel={TEXT.retryLoad}
      onRetry={retry}
      emptyText={t('mentions.empty')}
      {...(notice === undefined ? {} : { notice })}
    />
  );
}
