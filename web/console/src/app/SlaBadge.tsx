// The badge of an SLA goal's state (8a-sla screens.md 2·3절). The four badge tones are all there
// is, so 넘김 takes the warning tone of 마감일's 지남.
import { Badge } from '@popdesk/ui';
import type { Schemas } from '../api';
import { t } from './locale';

/** 넘김 and 못 지킴 in the warning tone, 곧 넘김 and 지킴 neutral; 괜찮음 and no goal have none. */
export function SlaBadge(props: { state: Schemas['SlaGoal']['state'] }) {
  switch (props.state) {
    case 'BREACHED':
      return <Badge tone="warning">{t('sla.breached')}</Badge>;
    case 'WARNING':
      return <Badge tone="neutral">{t('sla.warning')}</Badge>;
    case 'MET':
      return <Badge tone="neutral">{t('sla.met')}</Badge>;
    case 'MISSED':
      return <Badge tone="warning">{t('sla.missed')}</Badge>;
    default:
      return null;
  }
}
