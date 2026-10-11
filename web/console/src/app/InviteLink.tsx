// An invitation or reset link shown once (INVITE-6, S1A-AC-37): a read-only field and 복사. When the
// clipboard is not available the field is selected for Ctrl+C. The link is
// never logged or put in the address (MAIL-3).
import { Alert, CopyField } from '@popdesk/ui';
import { useState } from 'react';
import { useToast } from './toastContext';

const ONCE = '이 링크는 지금만 보입니다. 대화상자를 닫으면 다시 볼 수 없습니다.';
const BY_HAND = 'Ctrl+C로 복사하세요';

export function InviteLink(props: { link: string; intro: string; label?: string }) {
  const toast = useToast();
  const [status, setStatus] = useState<'idle' | 'copied' | 'unavailable'>('idle');

  const copy = async () => {
    try {
      // The Clipboard API is missing on plain http pages other than localhost.
      if (!('clipboard' in navigator)) throw new Error('no clipboard');
      await navigator.clipboard.writeText(props.link);
      setStatus('copied');
      toast('복사했습니다', 'info');
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
        label={props.label ?? '초대 링크'}
        value={props.link}
        status={status}
        copyLabel="복사"
        helpText={status === 'unavailable' ? BY_HAND : ONCE}
        onCopy={() => {
          void copy();
        }}
      />
    </div>
  );
}
