// C-8 고객사 목록 (관리자, screens.md 4절). List and 만들기 (PROJ-1).
import { Button, ConfirmDialog, DataTable, Dialog, EmptyState, StatusBadge, TextField, type DataTableColumn } from '@popdesk/ui';
import { useLayoutEffect, useRef, useState, type SubmitEvent } from 'react';
import { Link } from 'react-router';
import { api, type Schemas } from '../api';
import { FieldSummary, SaveFailure } from '../app/bands';
import { useMainBusy } from '../app/busy';
import { useFieldErrors, useLoad } from '../app/hooks';
import { LoadFailed, PageTitle } from '../app/layout';
import { TEXT } from '../app/messages';
import { useLiveNavigate } from '../app/navigate';
import { PageNav } from '../app/PageNav';
import { PAGE_SIZE, usePageMove, usePageParam } from '../app/paging';
import { call, type Failure } from '../app/result';
import { useToast } from '../app/toastContext';

type Company = Schemas['Company'];

const columns: DataTableColumn<Company>[] = [
  { key: 'code', header: '코드', cell: (c) => <span className="pd-key">{c.code}</span>, sticky: true, width: '7rem' },
  { key: 'name', header: '이름', cell: (c) => <Link to={`/companies/${encodeURIComponent(c.code)}`}>{c.name}</Link> },
  { key: 'legalName', header: '회사명', cell: (c) => c.legalName },
  { key: 'status', header: '상태', cell: (c) => <StatusBadge kind="project" status={c.status} /> },
];

const FORM_ID = 'create-company';

function CreateCompanyDialog(props: { open: boolean; onClose: () => void; onClosedWhileSaving: () => void }) {
  const navigate = useLiveNavigate();
  const toast = useToast();
  const [name, setName] = useState('');
  const [code, setCode] = useState('');
  const [legalName, setLegalName] = useState('');
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [askDiscard, setAskDiscard] = useState(false);
  const open = useRef(props.open);
  useLayoutEffect(() => {
    open.current = props.open;
  });
  const fields = useFieldErrors({
    name: { id: 'company-name', limits: { max: 100 } },
    code: { id: 'company-code', text: { INVALID_FORMAT: '대문자와 숫자 2~10자로 넣어 주세요.' } },
    legalName: { id: 'company-legal-name', limits: { max: 100 } },
  });

  const reset = () => {
    setName('');
    setCode('');
    setLegalName('');
    setFailure(null);
    setAskDiscard(false);
    fields.clear();
  };

  const close = () => {
    reset();
    props.onClose();
  };

  const requestClose = () => {
    if (!pending && (name !== '' || code !== '' || legalName !== '')) setAskDiscard(true);
    else close();
  };

  const submit = async (e: SubmitEvent) => {
    e.preventDefault();
    if (pending) return;
    setFailure(null);
    fields.clear();
    setPending(true);
    const result = await call(api.POST('/api/console/companies', { body: { name, code, legalName } }));
    setPending(false);
    if (!open.current) {
      // Closed while saving: the list shows the result (screens.md C-8 만들기 저장 중).
      props.onClosedWhileSaving();
      return;
    }
    if (result.ok) {
      toast(`고객사를 만들었습니다(${result.data.name})`);
      close();
      void navigate(`/companies/${encodeURIComponent(result.data.code)}`);
      return;
    }
    const f = result.failure;
    if (f.kind === 'invalid') fields.applyFields(f.fields, f.message);
    else if (f.kind === 'rejected' && f.code === 'COMPANY_CODE_TAKEN') fields.setError('code', '이미 쓰는 코드입니다.');
    else setFailure(f);
  };

  return (
    <>
      <Dialog
        open={props.open}
        title="고객사 만들기"
        closeLabel={TEXT.close}
        pending={pending}
        onClose={requestClose}
        footer={
          <>
            <Button variant="secondary" onClick={requestClose}>
              {TEXT.cancel}
            </Button>
            <Button type="submit" form={FORM_ID} pending={pending} pendingLabel={TEXT.sending}>
              만들기
            </Button>
          </>
        }
      >
        <form
          id={FORM_ID}
          className="pd-form"
          noValidate
          onSubmit={(e) => {
            void submit(e);
          }}
        >
          <FieldSummary summary={fields.summary} />
          <SaveFailure failure={failure} />
          <TextField
            id="company-name"
            label="이름"
            value={name}
            onChange={setName}
            maxLength={100}
            helpText="콘솔과 포털에 보이는 이름입니다."
            {...(fields.errors.name ? { error: fields.errors.name } : {})}
          />
          <TextField
            id="company-code"
            label="코드"
            value={code}
            onChange={(v) => {
              setCode(v.toUpperCase());
            }}
            maxLength={10}
            autoCapitalize="characters"
            spellCheck={false}
            helpText="대문자와 숫자 2~10자. 문의 키 앞에 붙습니다. 나중에 바꿀 수 없습니다."
            {...(fields.errors.code ? { error: fields.errors.code } : {})}
          />
          <TextField
            id="company-legal-name"
            label="회사명"
            value={legalName}
            onChange={setLegalName}
            maxLength={100}
            helpText="계약서에 쓰는 회사 이름입니다."
            {...(fields.errors.legalName ? { error: fields.errors.legalName } : {})}
          />
        </form>
      </Dialog>
      <ConfirmDialog
        open={askDiscard}
        title="쓰던 내용을 버릴까요?"
        message="닫으면 이 대화상자에 쓴 값이 사라집니다."
        confirmLabel="버리고 닫기"
        cancelLabel="계속 쓰기"
        tone="danger"
        onConfirm={close}
        onCancel={() => {
          setAskDiscard(false);
        }}
      />
    </>
  );
}

export function C8CompanyList() {
  const page = usePageParam();
  const heading = useRef<HTMLHeadingElement>(null);
  const [creating, setCreating] = useState(false);
  const load = useLoad(
    () => call(api.GET('/api/console/companies', { params: { query: { page, size: PAGE_SIZE } } })),
    String(page),
  );
  const firstLoad = load.status === 'loading' && load.data === undefined;
  useMainBusy(firstLoad);
  const move = usePageMove(load.data, heading);
  const data = load.data;
  const outOfRange = data !== undefined && data.items.length === 0 && page > 1;

  return (
    <div className="pd-screen">
      <div className="pd-screen-head">
        <PageTitle titleRef={heading}>고객사 목록</PageTitle>
        <Button
          onClick={() => {
            setCreating(true);
          }}
        >
          고객사 만들기
        </Button>
      </div>
      {load.status === 'failed' && (
        <LoadFailed
          onRetry={() => {
            void load.reload();
          }}
        />
      )}
      {(load.status !== 'failed' || data) && (
        <DataTable
          caption={`고객사 목록, ${String(data?.totalElements ?? 0)}건`}
          columns={columns}
          rows={data?.items ?? []}
          rowKey={(c) => c.code}
          status={firstLoad ? 'loading' : 'ready'}
          skeletonRows={load.slow ? 8 : 0}
          density="compact"
          empty={
            outOfRange ? (
              <EmptyState title="이 쪽에는 고객사가 없습니다" action={<Link to="/companies">1쪽으로</Link>} />
            ) : (
              <EmptyState title="첫 고객사를 만드세요. 고객사를 만들어야 고객을 초대할 수 있습니다." />
            )
          }
        />
      )}
      {data && <PageNav path="/companies" page={data.page} pageCount={data.totalPages} onPageChange={move.onPageChange} />}
      <p role="status" className="pd-sr-only">
        {move.announcement}
      </p>
      <CreateCompanyDialog
        open={creating}
        onClose={() => {
          setCreating(false);
        }}
        onClosedWhileSaving={() => {
          void load.reload();
        }}
      />
    </div>
  );
}
