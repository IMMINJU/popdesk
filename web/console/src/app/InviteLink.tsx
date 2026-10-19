// An invitation or reset link shown once (INVITE-6, S1A-AC-37): a read-only field and 복사. When the
// clipboard is not available the field is selected for Ctrl+C. The link is
// never logged or put in the address (MAIL-3).
import { Alert, CopyField } from '@popdesk/ui';
import { useState } from 'react';
import { useToast } from './toastContext';
import { t } from './locale';

export function InviteLink(props: { link: string; intro: string; label?: string }) {
  const toast = useToast();
  const [status, setStatus] = useState<'idle' | 'copied' | 'unavailable'>('idle');

  const copy = async () => {
    try {
      // The Clipboard API is missing on plain http pages other than localhost.
      if (!('clipboard' in navigator)) throw new Error('no clipboard');
      await navigator.clipboard.writeText(props.link);
      setStatus('copied');
      toast(t('inviteLink.copied'), 'info');
    } catch {
      setStatus('unavailable');
    }
  };

  return (
    <div className="pd-form">
      <Alert tone="warning" live="status">
        {props.intro}
      </Alert>
      <CopyField
        label={props.label ?? t('inviteLink.label')}
        value={props.link}
        status={status}
        copyLabel={t('inviteLink.copy')}
        helpText={status === 'unavailable' ? t('inviteLink.byHand') : t('inviteLink.once')}
        onCopy={() => {
          void copy();
        }}
      />
    </div>
  );
}
