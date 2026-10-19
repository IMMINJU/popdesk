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
import { byName } from './labelItems';
import { call, type Failure } from './result';
import { useToast } from './toastContext';
import { formatNumber, t, texts, tf } from './locale';

type Label = Schemas['Label'];

/** A company has at most 200 labels (S6A-AC-09). */
const COMPANY_LABEL_LIMIT = 200;
const NAME_MAX = 30;
const DEFAULT_COLOR = '#808080';
const WORDS = texts({
  taken: 'companyLabels.taken',
  limit: 'companyLabels.limit',
  nameMissing: 'companyLabels.nameMissing',
  nameLong: 'companyLabels.nameLong',
  color: 'companyLabels.colorFormat',
});

const LABEL_ERRORS: Readonly<Record<string, string>> = texts({
  LABEL_LIMIT_EXCEEDED: 'companyLabels.limit',
  COMPANY_NOT_ACTIVE: 'companyLabels.errNotActive',
  NOT_FOUND: 'companyLabels.errNotFound',
});

/** The eight swatches (screens.md 4절): each has text, black or white, of at least 4.5:1 on it. */
const SWATCHES = [
  ['companyLabels.gray', '#808080'],
  ['companyLabels.red', '#C0262D'],
  ['companyLabels.orange', '#B07A00'],
  ['companyLabels.yellow', '#F2C14E'],
  ['companyLabels.green', '#1F7A3A'],
  ['companyLabels.teal', '#0F766E'],
  ['companyLabels.blue', '#245FB0'],
  ['companyLabels.purple', '#6D28D9'],
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
    const nameProblem = sent === '' ? WORDS.nameMissing : codePoints(sent) > NAME_MAX ? WORDS.nameLong : undefined;
    const colorProblem = labelTextColor(color) === null || !color.trim().startsWith('#') ? WORDS.color : undefined;
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
      toast(editing ? t('companyLabels.editedToast') : t('companyLabels.createdToast'));
      props.onClose();
      return;
    }
    const f = result.failure;
    if (f.kind === 'rejected' && f.code === 'LABEL_NAME_TAKEN') setNameError(WORDS.taken);
    else if (f.kind === 'invalid') {
      for (const p of f.fields) {
        if (p.field === 'name') setNameError(p.code === 'TOO_LONG' ? WORDS.nameLong : WORDS.nameMissing);
        if (p.field === 'color') setColorError(WORDS.color);
      }
    } else setFailure(f);
  };

  const valid = labelTextColor(color) !== null;
  return (
    <Dialog
      open
      title={editing ? t('companyLabels.editTitle') : t('companyLabels.createTitle')}
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
            {editing ? t('companyLabels.save') : t('companyLabels.create')}
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
        <TextField label={t('companyLabels.name')} value={name} onChange={setName} maxLength={NAME_MAX} showCount {...(nameError === undefined ? {} : { error: nameError })} />
        <div className="pd-field">
          <span id="label-swatches" className="pd-label">
            {t('companyLabels.color')}
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
                <span className="pd-sr-only">{t(word)}</span>
              </label>
            ))}
          </div>
        </div>
        <TextField label={t('companyLabels.colorValue')} value={color} onChange={setColor} maxLength={7} spellCheck={false} {...(colorError === undefined ? {} : { error: colorError })} />
        <div className="pd-field">
          <span className="pd-label">{t('companyLabels.preview')}</span>
          <span>{trimmed(name) === '' ? <span className="pd-muted">{t('companyLabels.previewEmpty')}</span> : <LabelChip name={trimmed(name)} color={valid ? color.trim().toUpperCase() : DEFAULT_COLOR} />}</span>
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
      toast(t('companyLabels.deletedToast'));
      props.onClose();
      return;
    }
    setFailure(result.failure);
  };

  return (
    <ConfirmDialog
      open
      tone="danger"
      title={t('companyLabels.deleteTitle')}
      message={
        <>
          <p>{tf('companyLabels.deleteAsk', { name: label.name, count: formatNumber(label.issueCount) })}</p>
          <SaveFailure failure={failure} known={LABEL_ERRORS} />
        </>
      }
      confirmLabel={t('companyLabels.delete')}
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

  // The same order as the chips of C-3 and C-4 (S6A-AC-10): the API sorts by code point, the screens by the reader's language.
  const labels = byName(load.data?.items ?? []);
  const full = labels.length >= COMPANY_LABEL_LIMIT;

  const columns: DataTableColumn<Label>[] = [
    { key: 'name', header: t('companyLabels.label'), cell: (l) => <LabelChip name={l.name} color={l.color} /> },
    { key: 'issues', header: t('companyLabels.issues'), cell: (l) => tf('companyLabels.issueCount', { count: formatNumber(l.issueCount) }) },
    {
      key: 'actions',
      header: <span className="pd-sr-only">{t('companyLabels.actions')}</span>,
      align: 'end',
      cell: (l) => (
        <RowMenu
          label={tf('companyLabels.actionsOf', { name: l.name })}
          items={[
            {
              label: t('companyLabels.edit'),
              onSelect: () => {
                open({ what: 'edit', label: l });
              },
            },
            {
              label: t('companyLabels.delete'),
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
          {t('companyLabels.title')}
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
            {t('companyLabels.createTitle')}
          </Button>
        )}
      </div>
      {full && !props.archived && <p className="pd-muted">{WORDS.limit}</p>}
      {load.status === 'failed' && <LoadFailed onRetry={reload} />}
      {load.status !== 'failed' || load.data ? (
        <DataTable
          caption={tf('companyLabels.caption', { count: labels.length })}
          columns={columns}
          rows={labels}
          rowKey={(l) => String(l.id)}
          status={load.data === undefined ? 'loading' : 'ready'}
          density="compact"
          empty={<EmptyState title={t('companyLabels.none')} headingLevel={3} />}
        />
      ) : null}
      {(opened?.what === 'create' || opened?.what === 'edit') && (
        <LabelDialog key={openings} code={code} label={opened.what === 'edit' ? opened.label : null} onClose={close} onSaved={reload} />
      )}
      {opened?.what === 'delete' && <DeleteLabelDialog key={openings} code={code} label={opened.label} onClose={close} onDeleted={reload} />}
    </section>
  );
}
