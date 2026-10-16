// C-13 SLA 설정 (관리자, 8a-sla screens.md 1절). Goals for each priority, business hours, and holidays,
// saved as a whole with the version that was read: a 409 means another admin saved first.
import { Alert, Button, Checkbox, DataTable, PRIORITY_WORDS, PriorityLabel, RelativeTime, TextField, type DataTableColumn } from '@popdesk/ui';
import { useState } from 'react';
import { api, type Schemas } from '../api';
import { FieldSummary, SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useFieldErrors, useLive, useLoad, type FieldSpec } from '../app/hooks';
import { LoadFailed, PageTitle } from '../app/layout';
import { slaLength, TEXT } from '../app/messages';
import { subject } from '../app/particles';
import { call, type Failure, type FieldProblem } from '../app/result';

type SlaPolicy = Schemas['SlaPolicy'];
type ChangeSlaPolicyRequest = Schemas['ChangeSlaPolicyRequest'];
type Priority = Schemas['SlaTarget']['priority'];
type Weekday = Schemas['SlaHours']['weekday'];

const PRIORITIES: readonly Priority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT'];
const WEEKDAYS: readonly Weekday[] = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const DAY_WORDS: Readonly<Record<Weekday, string>> = {
  MONDAY: '월요일',
  TUESDAY: '화요일',
  WEDNESDAY: '수요일',
  THURSDAY: '목요일',
  FRIDAY: '금요일',
  SATURDAY: '토요일',
  SUNDAY: '일요일',
};

const MAX_HOLIDAYS = 500;
const MIDNIGHT = '24:00';
const NOTICE = '목표를 하나라도 정하면 그때부터 올라오는 문의에 기한이 붙습니다. 이미 있는 문의에는 붙지 않습니다.';
const ALL_DAY = '업무일을 하나도 고르지 않으면 하루 24시간으로 셉니다.';
const HOLIDAY_LIMIT = '쉬는 날은 500개까지입니다.';
const NOTHING_CHANGED = '바뀐 것이 없습니다.';
const SAVED = 'SLA 정책을 저장했습니다. 열린 문의의 기한을 다시 계산합니다.';
const OUT_OF_RANGE = '1분에서 525,600분 사이로 정합니다.';
const TOO_SHORT = '해결 목표는 첫 응답 목표보다 짧을 수 없습니다.';
const END_BEFORE_START = '끝 시각은 시작 시각보다 늦어야 합니다.';
const DAY_TAKEN = '이미 있는 날짜입니다.';
const CONFLICT = '다른 관리자가 먼저 바꿨습니다. 다시 읽은 뒤 고쳐 주세요.';
const READ_FAILED = 'SLA 정책을 읽지 못했습니다. 다시 해 보세요.';
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
      : { work, opens: '09:00', closes: '18:00', midnight: false };
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
    specs[firstField(i)] = { id: `sla-${p}-first`, text: { OUT_OF_RANGE } };
    specs[resolutionField(i)] = { id: `sla-${p}-resolution`, text: { OUT_OF_RANGE, [SHORTER]: TOO_SHORT } };
  });
  WEEKDAYS.forEach((d, i) => {
    specs[opensField(i)] = { id: `sla-${d}-opens` };
    specs[closesField(i)] = { id: `sla-${d}-closes`, text: { OUT_OF_RANGE: END_BEFORE_START } };
  });
  specs.holidays = { id: 'sla-holiday', text: { UNKNOWN_VALUE: DAY_TAKEN, TOO_MANY: HOLIDAY_LIMIT } };
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
          SLA가 켜져 있습니다. 버전 {policy.version}, <RelativeTime dateTime={policy.changedAt} />{' '}
          {policy.changedBy === null ? '시간대가 바뀌어 시스템이 바꿈' : `${subject(policy.changedBy.name)} 바꿈`}
        </p>
      ) : (
        <p>SLA가 꺼져 있습니다.</p>
      )}
      <p className="pd-muted">
        시간대: {policy.timeZone}(설정 파일의 <code className="pd-code">POPDESK_TIMEZONE</code>)
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
      setHolidayError(DAY_TAKEN);
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
      setNotice(NOTHING_CHANGED);
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
      setNotice(SAVED);
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
        { key: 'priority', header: '우선순위', cell: (p) => <PriorityLabel priority={p} /> },
        {
          key: 'first',
          header: '첫 응답(분)',
          cell: (p) => {
            const i = PRIORITIES.indexOf(p);
            return (
              <TextField
                id={`sla-${p}-first`}
                label={`${PRIORITY_WORDS[p]} 첫 응답(분)`}
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
          header: '해결(분)',
          cell: (p) => {
            const i = PRIORITIES.indexOf(p);
            return (
              <TextField
                id={`sla-${p}-resolution`}
                label={`${PRIORITY_WORDS[p]} 해결(분)`}
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
      <PageTitle>SLA 설정</PageTitle>
      <p className="pd-muted">{NOTICE}</p>
      {load.status === 'failed' && <LoadFailed text={READ_FAILED} onRetry={reload} />}
      {policy && draft && (
        <>
          <StatusLine policy={policy} />
          <FieldSummary summary={fields.summary} />
          {conflict && (
            <Alert
              tone="error"
              action={
                <Button variant="secondary" size="sm" onClick={reload}>
                  다시 불러오기
                </Button>
              }
            >
              {CONFLICT}
            </Alert>
          )}
          <SaveFailure failure={failure} />

          <section className="pd-section" aria-labelledby="sla-goals-title">
            <h2 id="sla-goals-title" className="pd-section-title">
              목표
            </h2>
            <div className="pd-sla-goals">
              <DataTable caption="목표" columns={columns} rows={PRIORITIES} rowKey={(p) => p} density="compact" />
            </div>
          </section>

          <section className="pd-section" aria-labelledby="sla-hours-title">
            <h2 id="sla-hours-title" className="pd-section-title">
              업무 시간
            </h2>
            <div className="pd-sla-days">
              {WEEKDAYS.map((d, i) => {
                const day = draft.hours[d];
                return (
                  <div key={d} className="pd-sla-day">
                    <Checkbox
                      label={`${DAY_WORDS[d]} 업무일`}
                      checked={day.work}
                      onChange={(work) => {
                        editDay(d, { work });
                      }}
                    />
                    {day.work && (
                      <div className="pd-sla-times">
                        <TextField
                          id={`sla-${d}-opens`}
                          label={`${DAY_WORDS[d]} 시작`}
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
                            label={`${DAY_WORDS[d]} 끝`}
                            type="time"
                            value={day.closes}
                            {...errorProp(fields.errors[closesField(i)])}
                            onChange={(closes) => {
                              editDay(d, { closes });
                            }}
                          />
                        )}
                        <Checkbox
                          label={`${DAY_WORDS[d]} 자정까지`}
                          checked={day.midnight}
                          onChange={(midnight) => {
                            editDay(d, { midnight });
                          }}
                        />
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
            <p className="pd-muted">{ALL_DAY}</p>
          </section>

          <section className="pd-section" aria-labelledby="sla-holidays-title">
            <h2 id="sla-holidays-title" className="pd-section-title">
              쉬는 날
            </h2>
            <div className="pd-sla-holiday-add">
              <TextField
                id="sla-holiday"
                label="날짜"
                type="date"
                value={holiday}
                {...errorProp(holidayError ?? fields.errors.holidays)}
                onChange={(value) => {
                  setHoliday(value);
                  setHolidayError(undefined);
                }}
              />
              <Button variant="secondary" disabled={full} onClick={addHoliday}>
                더하기
              </Button>
            </div>
            {full && <p className="pd-muted">{HOLIDAY_LIMIT}</p>}
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
                      <span className="pd-sr-only">{h}</span> 빼기
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {notice !== null && <Alert tone={notice === SAVED ? 'success' : 'info'}>{notice}</Alert>}
          <div className="pd-form-actions">
            <Button
              pending={saving}
              pendingLabel={TEXT.sending}
              onClick={() => {
                void save();
              }}
            >
              저장
            </Button>
          </div>
        </>
      )}
    </div>
  );
}
