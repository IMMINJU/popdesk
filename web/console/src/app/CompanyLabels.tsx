// C-9 "라벨" (6a-labels screens.md 4절, admins only like the whole screen): the company's labels in
// name order with the number of issues on each, and 만들기, 고치기, 지우기. Every dialog is mounted
// anew for each opening. An answer that comes after its dialog closed reads the list again when it
// succeeded and is dropped when it failed; after a move to another company the section is gone and
// drops it too (S6A-AC-86).
import {
  Button,
  ConfirmDialog,
  codePoints,
  DataTable,
  Dialog,
  EmptyState,
  LabelChip,
  RowMenu,
  TextField,
  labelTextColor,
  type DataTableColumn,
} from '@popdesk/ui';
import { useState } from 'react';
import { api, type Schemas } from '../api';
import { SaveFailure } from './bands';
import { useLive, useLoad } from './hooks';
import { LoadFailed } from './layout';
import { TEXT } from './messages';
import { call, type Failure } from './result';
import { useToast } from './toastContext';

type Label = Schemas['Label'];

/** A company has at most 200 labels (S6A-AC-09). */
const COMPANY_LABEL_LIMIT = 200;
const NAME_MAX = 30;
const DEFAULT_COLOR = '#808080';
const TAKEN_TEXT = '이 고객사에 같은 이름의 라벨이 있습니다.';
const LIMIT_TEXT = '라벨은 고객사마다 200개까지 만듭니다.';
const NAME_MISSING_TEXT = '이름을 넣어 주세요.';
const NAME_LONG_TEXT = '이름은 30자까지 씁니다.';
const COLOR_TEXT = '#과 16진수 여섯 자리로 적어 주세요. 예: #808080';

const LABEL_ERRORS: Record<string, string> = {
  LABEL_LIMIT_EXCEEDED: LIMIT_TEXT,
  COMPANY_NOT_ACTIVE: '보관된 고객사에는 라벨을 만들 수 없습니다.',
  NOT_FOUND: '이미 지워진 라벨입니다.',
};

/** The eight swatches (screens.md 4절): each has text, black or white, of at least 4.5:1 on it. */
const SWATCHES = [
  ['회색', '#808080'],
  ['빨강', '#C0262D'],
  ['주황', '#B07A00'],
  ['노랑', '#F2C14E'],
  ['초록', '#1F7A3A'],
  ['청록', '#0F766E'],
  ['파랑', '#245FB0'],
  ['보라', '#6D28D9'],
] as const;

/** The name as the server keeps it: no space around it, one form of each letter. */
function trimmed(name: string): string {
  return name.trim().normalize('NFC');
}

function LabelDialog(props: { code: string; label: Label | null; onClose: () => void; onSaved: () => void }) {
  const live = useLive();
  const toast = useToast();
  const [name, setName] = useState(props.label?.name ?? '');
  const [color, setColor] = useState(props.label?.color ?? DEFAULT_COLOR);
  const [pending, setPending] = useState(false);
  const [nameError, setNameError] = useState<string | undefined>(undefined);
  const [colorError, setColorError] = useState<string | undefined>(undefined);
  const [failure, setFailure] = useState<Failure | null>(null);
  const editing = props.label !== null;

  const save = async () => {
    if (pending) return;
    const sent = trimmed(name);
    const nameProblem = sent === '' ? NAME_MISSING_TEXT : codePoints(sent) > NAME_MAX ? NAME_LONG_TEXT : undefined;
    const colorProblem = labelTextColor(color) === null || !color.trim().startsWith('#') ? COLOR_TEXT : undefined;
    setNameError(nameProblem);
    setColorError(colorProblem);
    setFailure(null);
    if (nameProblem !== undefined || colorProblem !== undefined) return;
    setPending(true);
    const body = { name: sent, color: color.trim().toUpperCase() };
    const result = props.label
      ? await call(api.PATCH('/api/console/companies/{code}/labels/{id}', { params: { path: { code: props.code, id: props.label.id } }, body }))
      : await call(api.POST('/api/console/companies/{code}/labels', { params: { path: { code: props.code } }, body }));
    // Done by the server even when the dialog has closed meanwhile: the list is read again.
    if (result.ok) props.onSaved();
    if (!live()) return;
    setPending(false);
    if (result.ok) {
      toast(editing ? '라벨을 고쳤습니다' : '라벨을 만들었습니다');
      props.onClose();
      return;
    }
    const f = result.failure;
    if (f.kind === 'rejected' && f.code === 'LABEL_NAME_TAKEN') setNameError(TAKEN_TEXT);
    else if (f.kind === 'invalid') {
      for (const p of f.fields) {
        if (p.field === 'name') setNameError(p.code === 'TOO_LONG' ? NAME_LONG_TEXT : NAME_MISSING_TEXT);
        if (p.field === 'color') setColorError(COLOR_TEXT);
      }
    } else setFailure(f);
  };

  const valid = labelTextColor(color) !== null;
  return (
    <Dialog
      open
      title={editing ? '라벨 고치기' : '라벨 만들기'}
      closeLabel={TEXT.close}
      pending={pending}
      onClose={props.onClose}
      footer={
        <>
          <Button variant="secondary" onClick={props.onClose}>
            {TEXT.cancel}
          </Button>
          <Button
            pending={pending}
            pendingLabel={TEXT.sending}
            onClick={() => {
              void save();
            }}
          >
            {editing ? '저장' : '만들기'}
          </Button>
        </>
      }
    >
      <form
        className="pd-form"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void save();
        }}
      >
        <SaveFailure failure={failure} known={LABEL_ERRORS} />
        <TextField label="이름" value={name} onChange={setName} maxLength={NAME_MAX} showCount {...(nameError === undefined ? {} : { error: nameError })} />
        <div className="pd-field">
          <span id="label-swatches" className="pd-label">
            색
          </span>
          <div className="pd-swatches" role="radiogroup" aria-labelledby="label-swatches">
            {SWATCHES.map(([word, value]) => (
              <label key={value} className="pd-swatch" style={{ backgroundColor: value }}>
                <input
                  type="radio"
                  className="pd-swatch-input"
                  name="label-color"
                  value={value}
                  checked={color.trim().toUpperCase() === value}
                  onChange={() => {
                    setColor(value);
                    setColorError(undefined);
                  }}
                />
                <span className="pd-sr-only">{word}</span>
              </label>
            ))}
          </div>
        </div>
        <TextField label="색 값 (#RRGGBB)" value={color} onChange={setColor} maxLength={7} spellCheck={false} {...(colorError === undefined ? {} : { error: colorError })} />
        <div className="pd-field">
          <span className="pd-label">미리 보기</span>
          <span>{trimmed(name) === '' ? <span className="pd-muted">이름을 넣으면 보입니다</span> : <LabelChip name={trimmed(name)} color={valid ? color.trim().toUpperCase() : DEFAULT_COLOR} />}</span>
        </div>
      </form>
    </Dialog>
  );
}

function DeleteLabelDialog(props: { code: string; label: Label; onClose: () => void; onDeleted: () => void }) {
  const live = useLive();
  const toast = useToast();
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const { label } = props;

  const remove = async () => {
    if (pending) return;
    setPending(true);
    setFailure(null);
    const result = await call(api.DELETE('/api/console/companies/{code}/labels/{id}', { params: { path: { code: props.code, id: label.id } } }));
    if (result.ok) props.onDeleted();
    if (!live()) return;
    setPending(false);
    if (result.ok) {
      toast('라벨을 지웠습니다');
      props.onClose();
      return;
    }
    setFailure(result.failure);
  };

  return (
    <ConfirmDialog
      open
      tone="danger"
      title="라벨 지우기"
      message={
        <>
          <p>
            라벨 &apos;{label.name}&apos;을 지웁니다. 붙어 있는 문의 {label.issueCount.toLocaleString('ko-KR')}건에서도 떨어집니다. 이력에는 남지 않습니다.
          </p>
          <SaveFailure failure={failure} known={LABEL_ERRORS} />
        </>
      }
      confirmLabel="지우기"
      cancelLabel={TEXT.cancel}
      pending={pending}
      pendingLabel={TEXT.sending}
      onConfirm={() => {
        void remove();
      }}
      onCancel={props.onClose}
    />
  );
}

type Opened = { what: 'create' } | { what: 'edit'; label: Label } | { what: 'delete'; label: Label };

export function CompanyLabels(props: { code: string; archived: boolean }) {
  const { code } = props;
  const live = useLive();
  const load = useLoad(() => call(api.GET('/api/console/companies/{code}/labels', { params: { path: { code } } })), code);
  const [opened, setOpened] = useState<Opened | null>(null);
  // Counts openings: the key that makes every opening a new dialog.
  const [openings, setOpenings] = useState(0);

  const open = (next: Opened) => {
    setOpenings((n) => n + 1);
    setOpened(next);
  };
  const close = () => {
    setOpened(null);
  };
  const reload = () => {
    if (live()) void load.reload();
  };

  const labels = load.data?.items ?? [];
  const full = labels.length >= COMPANY_LABEL_LIMIT;

  const columns: DataTableColumn<Label>[] = [
    { key: 'name', header: '라벨', cell: (l) => <LabelChip name={l.name} color={l.color} /> },
    { key: 'issues', header: '붙은 문의', cell: (l) => `문의 ${l.issueCount.toLocaleString('ko-KR')}건` },
    {
      key: 'actions',
      header: <span className="pd-sr-only">동작</span>,
      align: 'end',
      cell: (l) => (
        <RowMenu
          label={`${l.name} 동작`}
          items={[
            {
              label: '고치기',
              onSelect: () => {
                open({ what: 'edit', label: l });
              },
            },
            {
              label: '지우기',
              tone: 'danger',
              onSelect: () => {
                open({ what: 'delete', label: l });
              },
            },
          ]}
        />
      ),
    },
  ];

  return (
    <section id="labels" className="pd-section pd-company-labels" aria-labelledby="labels-title">
      <div className="pd-section-head">
        <h2 id="labels-title" className="pd-section-title">
          라벨
        </h2>
        {/* An archived company takes no new label; its labels can still be changed and deleted. */}
        {!props.archived && (
          <Button
            variant="secondary"
            size="sm"
            disabled={full}
            onClick={() => {
              open({ what: 'create' });
            }}
          >
            라벨 만들기
          </Button>
        )}
      </div>
      {full && !props.archived && <p className="pd-muted">{LIMIT_TEXT}</p>}
      {load.status === 'failed' && <LoadFailed onRetry={reload} />}
      {load.status !== 'failed' || load.data ? (
        <DataTable
          caption={`라벨, ${String(labels.length)}개`}
          columns={columns}
          rows={labels}
          rowKey={(l) => String(l.id)}
          status={load.data === undefined ? 'loading' : 'ready'}
          density="compact"
          empty={<EmptyState title="아직 라벨이 없습니다." headingLevel={3} />}
        />
      ) : null}
      {(opened?.what === 'create' || opened?.what === 'edit') && (
        <LabelDialog key={openings} code={code} label={opened.what === 'edit' ? opened.label : null} onClose={close} onSaved={reload} />
      )}
      {opened?.what === 'delete' && <DeleteLabelDialog key={openings} code={code} label={opened.label} onClose={close} onDeleted={reload} />}
    </section>
  );
}
