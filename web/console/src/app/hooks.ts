// The shared hooks with this app's texts.
import { useDocumentTitle as useDocumentTitleWith, useFieldErrors as useFieldErrorsWith, type FieldErrors, type FieldSpec } from '@popdesk/app/hooks';
import { fieldSummaryTitle, fieldText, TEXT } from './messages';

export {
  SKELETON_DELAY_MS,
  useCooldown,
  useFocusWhenReady,
  useLeaveGuard,
  useLive,
  useLoad,
  type FieldErrors,
  type FieldSpec,
  type Load,
} from '@popdesk/app/hooks';

const FIELD_WORDS = { fieldText, fieldSummaryTitle };

export function useFieldErrors<F extends string>(specs: Record<F, FieldSpec>): FieldErrors<F> {
  return useFieldErrorsWith(specs, FIELD_WORDS);
}

export function useDocumentTitle(title: string | null) {
  useDocumentTitleWith(title, TEXT.displayName);
}
