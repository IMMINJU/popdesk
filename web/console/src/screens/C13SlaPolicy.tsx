// C-13 SLA 설정 (관리자, 8a-sla screens.md 1절). Goals for each priority, business hours, and holidays,
// saved as a whole with the version that was read: a 409 means another admin saved first.
import { Alert, Button, Checkbox, DataTable, PRIORITY_WORDS, PriorityLabel, RelativeTime, TextField, type DataTableColumn } from '@popdesk/ui';
import { useState } from 'react';
import { api, type Schemas } from '../api';
import { FieldSummary, SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useFieldErrors, useLive, useLoad, type FieldSpec } from '../app/hooks';
import { LoadFailed, PageTitle } from '../app/layout';
import { screenLocale, t, texts, tf } from '../app/locale';
import { slaLength, TEXT } from '../app/messages';
import { subject } from '../app/particles';
import { call, type Failure, type FieldProblem } from '../app/result';

type SlaPolicy = Schemas['SlaPolicy'];
type ChangeSlaPolicyRequest = Schemas['ChangeSlaPolicyRequest'];
type Priority = Schemas['SlaTarget']['priority'];
type Weekday = Schemas['SlaHours']['weekday'];

const PRIORITIES: readonly Priority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT'];
const WEEKDAYS: readonly Weekday[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const DAY_WORDS: Readonly<Record<Weekday, string>> = texts({
  MONDAY: 'slaPolicy.monday',
  TUESDAY: 'slaPolicy.tuesday',
  WEDNESDAY: 'slaPolicy.wednesday',
  THURSDAY: 'slaPolicy.thursday',
  FRIDAY: 'slaPolicy.friday',
  SATURDAY: 'slaPolicy.saturday',
  SUNDAY: 'slaPolicy.sunday',
});

const MAX_HOLIDAYS = 500;
const MIDNIGHT = '24:00';
/** The hours a workday starts with when it is first turned on. */
const DEFAULT_OPENS = '09:00';
const DEFAULT_CLOSES = '18:00';
// Read in the screen language each time a field is read.
const TEXT_SLA = texts({
  notice: 'slaPolicy.notice',
  allDay: 'slaPolicy.allDay',
  holidayLimit: 'slaPolicy.holidayLimit',
  nothingChanged: 'slaPolicy.nothingChanged',
  saved: 'slaPolicy.saved',
  dayTaken: 'slaPolicy.dayTaken',
  conflict: 'slaPolicy.conflict',
  readFailed: 'slaPolicy.readFailed',
});
/** The screen's own code for a resolution goal shorter than the first response goal (the server says OUT_OF_RANGE). */
const SHORTER = 'SHORTER_THAN_FIRST_RESPONSE';

interface GoalDraft {
  first: string;
  resolution: string;
}

interface DayDraft {
  work: boolean;
  opens: string;
  closes: string;
  midnight: boolean;
}

interface Draft {
  targets: Record<Priority, GoalDraft>;
  hours: Record<Weekday, DayDraft>;
  holidays: string[];
}

function minutesText(value: number | null): string {
  return value === null ? '' : String(value);
}

function draftOf(policy: SlaPolicy): Draft {
  const targets = {} as Record<Priority, GoalDraft>;
  for (const p of PRIORITIES) {
    const t = policy.targets.find((x) => x.priority === p);
    targets[p] = { first: minutesText(t?.firstResponseMinutes ?? null), resolution: minutesText(t?.resolutionMinutes ?? null) };
  }
  const hours = {} as Record<Weekday, DayDraft>;
  for (const d of WEEKDAYS) {
    const h = policy.hours.find((x) => x.weekday === d);
    const work = h !== undefined && h.opens !== null && h.closes !== null;
    hours[d] = work
      ? { work, opens: h.opens ?? '', closes: h.closes === MIDNIGHT ? '' : (h.closes ?? ''), midnight: h.closes === MIDNIGHT }
      : { work, opens: DEFAULT_OPENS, closes: DEFAULT_CLOSES, midnight: false };
  }
  return { targets, hours, holidays: [...policy.holidays].sort() };
}

function minutesOf(text: string): number | null {
  return text.trim() === '' ? null : Number(text);
}

function bodyOf(draft: Draft, version: number): ChangeSlaPolicyRequest {
  return {
    version,
    targets: PRIORITIES.map((priority) => ({
      priority,
      firstResponseMinutes: minutesOf(draft.targets[priority].first),
      resolutionMinutes: minutesOf(draft.targets[priority].resolution),
    })),
    hours: WEEKDAYS.map((weekday) => {
      const day = draft.hours[weekday];
      if (!day.work) return { weekday, opens: null, closes: null };
      return { weekday, opens: day.opens === '' ? null : day.opens, closes: day.midnight ? MIDNIGHT : day.closes === '' ? null : day.closes };
    }),
    holidays: draft.holidays,
  };
}

function sameBody(a: ChangeSlaPolicyRequest, b: ChangeSlaPolicyRequest): boolean {
  return JSON.stringify([a.targets, a.hours, a.holidays]) === JSON.stringify([b.targets, b.hours, b.holidays]);
}

const firstField = (i: number) => `targets[${String(i)}].firstResponseMinutes`;
const resolutionField = (i: number) => `targets[${String(i)}].resolutionMinutes`;
const opensField = (i: number) => `hours[${String(i)}].opens`;
const closesField = (i: number) => `hours[${String(i)}].closes`;

/** The fields of the form in its order, named as the server names them. */
function fieldSpecs(): Record<string, FieldSpec> {
  const specs: Record<string, FieldSpec> = {};
  PRIORITIES.forEach((p, i) => {
    specs[firstField(i)] = { id: `sla-${p}-first`, text: texts({ OUT_OF_RANGE: 'slaPolicy.outOfRange' }) };
    specs[resolutionField(i)] = { id: `sla-${p}-resolution`, text: texts({ OUT_OF_RANGE: 'slaPolicy.outOfRange', [SHORTER]: 'slaPolicy.tooShort' }) };
  });
  WEEKDAYS.forEach((d, i) => {
    specs[opensField(i)] = { id: `sla-${d}-opens` };
    specs[closesField(i)] = { id: `sla-${d}-closes`, text: texts({ OUT_OF_RANGE: 'slaPolicy.endBeforeStart' }) };
  });
  specs.holidays = { id: 'sla-holiday', text: texts({ UNKNOWN_VALUE: 'slaPolicy.dayTaken', TOO_MANY: 'slaPolicy.holidayLimit' }) };
  return specs;
}

const SPECS = fieldSpecs();

/**
 * The server names a resolution goal out of range and one shorter than the first response goal
 * alike (`OUT_OF_RANGE`): the values that were sent tell them apart. A day given twice is shown
 * at the date field.
 */
function problemsFor(fields: FieldProblem[], body: ChangeSlaPolicyRequest): FieldProblem[] {
  return fields.map((problem) => {
    const at = PRIORITIES.findIndex((_, i) => problem.field === resolutionField(i));
    const sent = body.targets[at];
    if (problem.code === 'OUT_OF_RANGE' && sent) {
      const { firstResponseMinutes: first, resolutionMinutes: resolution } = sent;
      if (first !== null && resolution !== null && resolution >= 1 && resolution <= 525600 && resolution < first) return { ...problem, code: SHORTER };
    }
    if (/^holidays\[\d+\]$/.test(problem.field)) return { field: 'holidays', code: problem.code };
    return problem;
  });
}

/** "= 8시간" under a goal field holding a whole number of minutes. */
function goalHelp(text: string): string | undefined {
  const minutes = minutesOf(text);
  return minutes !== null && Number.isInteger(minutes) && minutes >= 1 ? `= ${slaLength(minutes)}` : undefined;
}

// Optional props are left out, not given as undefined (exactOptionalPropertyTypes).
const errorProp = (text: string | undefined) => (text === undefined ? {} : { error: text });
const helpProp = (text: string | undefined) => (text === undefined ? {} : { helpText: text });

function StatusLine(props: { policy: SlaPolicy }) {
  const { policy } = props;
  const on = policy.targets.some((t) => t.firstResponseMinutes !== null || t.resolutionMinutes !== null);
  return (
    <div className="pd-sla-status">
      {on ? (
        <p>
          {tf('slaPolicy.on', { version: policy.version })} <RelativeTime dateTime={policy.changedAt} />{' '}
          {policy.changedBy === null
            ? t('slaPolicy.changedBySystem')
            : // The Korean sentence picks its particle; the English one takes the name as it is.
              tf('slaPolicy.changedBy', { name: screenLocale() === 'ko' ? subject(policy.changedBy.name) : policy.changedBy.name })}
        </p>
      ) : (
        <p>{t('slaPolicy.off')}</p>
      )}
      <p className="pd-muted">
        {tf('slaPolicy.timeZoneBefore', { timeZone: policy.timeZone })}
        <code className="pd-code">POPDESK_TIMEZONE</code>
        {t('slaPolicy.timeZoneAfter')}
      </p>
    </div>
  );
}

export function C13SlaPolicy() {
  const live = useLive();
  const load = useLoad(() => call(api.GET('/api/console/sla-policy')), 'sla-policy');
  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const fields = useFieldErrors(SPECS);
  const [policy, setPolicy] = useState<SlaPolicy | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [holiday, setHoliday] = useState('');
  const [holidayError, setHolidayError] = useState<string | undefined>(undefined);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);

  // Each policy read (the first, and 다시 불러오기) replaces what was being changed.
  const [read, setRead] = useState<SlaPolicy | undefined>(undefined);
  if (load.data !== undefined && load.data !== read) {
    setRead(load.data);
    setPolicy(load.data);
    setDraft(draftOf(load.data));
    fields.clear();
  }

  const edit = (change: (d: Draft) => Draft) => {
    setNotice(null);
    setDraft((d) => (d === null ? d : change(d)));
  };
  const editGoal = (p: Priority, part: keyof GoalDraft, value: string) => {
    edit((d) => ({ ...d, targets: { ...d.targets, [p]: { ...d.targets[p], [part]: value } } }));
  };
  const editDay = (day: Weekday, change: Partial<DayDraft>) => {
    edit((d) => ({ ...d, hours: { ...d.hours, [day]: { ...d.hours[day], ...change } } }));
  };

  const addHoliday = () => {
    if (draft === null || holiday === '' || draft.holidays.length >= MAX_HOLIDAYS) return;
    if (draft.holidays.includes(holiday)) {
      setHolidayError(TEXT_SLA.dayTaken);
      return;
    }
    edit((d) => ({ ...d, holidays: [...d.holidays, holiday].sort() }));
    setHoliday('');
    setHolidayError(undefined);
  };

  const reload = () => {
    setConflict(false);
    setFailure(null);
    setNotice(null);
    void load.reload();
  };

  const save = async () => {
    if (policy === null || draft === null || saving) return;
    const body = bodyOf(draft, policy.version);
    setConflict(false);
    setFailure(null);
    if (sameBody(body, bodyOf(draftOf(policy), policy.version))) {
      fields.clear();
      setNotice(TEXT_SLA.nothingChanged);
      return;
    }
    setNotice(null);
    setSaving(true);
    const result = await call(api.PUT('/api/console/sla-policy', { body }));
    // An answer for a screen that is gone is dropped.
    if (!live()) return;
    setSaving(false);
    if (result.ok) {
      fields.clear();
      setPolicy(result.data);
      setDraft(draftOf(result.data));
      setNotice(TEXT_SLA.saved);
    } else if (result.failure.kind === 'invalid') {
      fields.applyFields(problemsFor(result.failure.fields, body), result.failure.message);
    } else if (result.failure.kind === 'rejected' && result.failure.code === 'VERSION_CONFLICT') {
      fields.clear();
      setConflict(true);
    } else {
      setFailure(result.failure);
    }
  };

  const columns: DataTableColumn<Priority>[] = draft
    ? [
        { key: 'priority', header: t('slaPolicy.priority'), cell: (p) => <PriorityLabel priority={p} /> },
        {
          key: 'first',
          header: t('slaPolicy.first'),
          cell: (p) => {
            const i = PRIORITIES.indexOf(p);
            return (
              <TextField
                id={`sla-${p}-first`}
                label={tf('slaPolicy.firstOf', { priority: PRIORITY_WORDS[p] })}
                type="number"
                min={1}
                max={525600}
                value={draft.targets[p].first}
                {...helpProp(goalHelp(draft.targets[p].first))}
                {...errorProp(fields.errors[firstField(i)])}
                onChange={(value) => {
                  editGoal(p, 'first', value);
                }}
              />
            );
          },
        },
        {
          key: 'resolution',
          header: t('slaPolicy.resolution'),
          cell: (p) => {
            const i = PRIORITIES.indexOf(p);
            return (
              <TextField
                id={`sla-${p}-resolution`}
                label={tf('slaPolicy.resolutionOf', { priority: PRIORITY_WORDS[p] })}
                type="number"
                min={1}
                max={525600}
                value={draft.targets[p].resolution}
                {...helpProp(goalHelp(draft.targets[p].resolution))}
                {...errorProp(fields.errors[resolutionField(i)])}
                onChange={(value) => {
                  editGoal(p, 'resolution', value);
                }}
              />
            );
          },
        },
      ]
    : [];

  const full = draft !== null && draft.holidays.length >= MAX_HOLIDAYS;

  return (
    <div className="pd-screen pd-sla">
      <PageTitle>{t('slaPolicy.title')}</PageTitle>
      <p className="pd-muted">{TEXT_SLA.notice}</p>
      {load.status === 'failed' && <LoadFailed text={TEXT_SLA.readFailed} onRetry={reload} />}
      {policy && draft && (
        <>
          <StatusLine policy={policy} />
          <FieldSummary summary={fields.summary} />
          {conflict && (
            <Alert
              tone="error"
              action={
                <Button variant="secondary" size="sm" onClick={reload}>
                  {TEXT.retryLoad}
                </Button>
              }
            >
              {TEXT_SLA.conflict}
            </Alert>
          )}
          <SaveFailure failure={failure} />

          <section className="pd-section" aria-labelledby="sla-goals-title">
            <h2 id="sla-goals-title" className="pd-section-title">
              {t('slaPolicy.goals')}
            </h2>
            <div className="pd-sla-goals">
              <DataTable caption={t('slaPolicy.goals')} columns={columns} rows={PRIORITIES} rowKey={(p) => p} density="compact" />
            </div>
          </section>

          <section className="pd-section" aria-labelledby="sla-hours-title">
            <h2 id="sla-hours-title" className="pd-section-title">
              {t('slaPolicy.hours')}
            </h2>
            <div className="pd-sla-days">
              {WEEKDAYS.map((d, i) => {
                const day = draft.hours[d];
                return (
                  <div key={d} className="pd-sla-day">
                    <Checkbox
                      label={tf('slaPolicy.workday', { day: DAY_WORDS[d] })}
                      checked={day.work}
                      onChange={(work) => {
                        editDay(d, { work });
                      }}
                    />
                    {day.work && (
                      <div className="pd-sla-times">
                        <TextField
                          id={`sla-${d}-opens`}
                          label={tf('slaPolicy.opens', { day: DAY_WORDS[d] })}
                          type="time"
                          value={day.opens}
                          {...errorProp(fields.errors[opensField(i)])}
                          onChange={(opens) => {
                            editDay(d, { opens });
                          }}
                        />
                        {!day.midnight && (
                          <TextField
                            id={`sla-${d}-closes`}
                            label={tf('slaPolicy.closes', { day: DAY_WORDS[d] })}
                            type="time"
                            value={day.closes}
                            {...errorProp(fields.errors[closesField(i)])}
                            onChange={(closes) => {
                              editDay(d, { closes });
                            }}
                          />
                        )}
                        <Checkbox
                          label={tf('slaPolicy.midnight', { day: DAY_WORDS[d] })}
                          checked={day.midnight}
                          onChange={(midnight) => {
                            // A day read as 24:00 has no end time of its own: turning 자정까지 off starts at 18:00, as a new workday does.
                            editDay(d, midnight || day.closes !== '' ? { midnight } : { midnight, closes: DEFAULT_CLOSES });
                          }}
                        />
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
            <p className="pd-muted">{TEXT_SLA.allDay}</p>
          </section>

          <section className="pd-section" aria-labelledby="sla-holidays-title">
            <h2 id="sla-holidays-title" className="pd-section-title">
              {t('slaPolicy.holidays')}
            </h2>
            <div className="pd-sla-holiday-add">
              <TextField
                id="sla-holiday"
                label={t('slaPolicy.date')}
                type="date"
                value={holiday}
                {...errorProp(holidayError ?? fields.errors.holidays)}
                onChange={(value) => {
                  setHoliday(value);
                  setHolidayError(undefined);
                }}
              />
              <Button variant="secondary" disabled={full} onClick={addHoliday}>
                {t('slaPolicy.add')}
              </Button>
            </div>
            {full && <p className="pd-muted">{TEXT_SLA.holidayLimit}</p>}
            {draft.holidays.length > 0 && (
              <ul className="pd-sla-holidays">
                {draft.holidays.map((h) => (
                  <li key={h}>
                    <span>{h}</span>
                    <Button
                      variant="quiet"
                      size="sm"
                      onClick={() => {
                        edit((x) => ({ ...x, holidays: x.holidays.filter((y) => y !== h) }));
                      }}
                    >
                      <span className="pd-sr-only">{h}</span> {t('slaPolicy.remove')}
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {notice !== null && <Alert tone={notice === TEXT_SLA.saved ? 'success' : 'info'}>{notice}</Alert>}
          <div className="pd-form-actions">
            <Button
              pending={saving}
              pendingLabel={TEXT.sending}
              onClick={() => {
                void save();
              }}
            >
              {t('slaPolicy.save')}
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
