# 2번 묶음 설계

> 수용 기준(`acceptance.md`)을 어떻게 만들지 가운데, 나중에 바꾸기 어려운 것만 적는다. API 경로와 응답 모양은 계약 단계(C-4)의 컨트롤러 껍데기가 정한다.
> 묶음이 끝나면 남길 것은 `spec/architecture.md`, `adr.md`, `docs/`로 옮기고 이 파일은 지운다.
> 표시: [소스]는 레포에서 본 것, [추론]은 확인하지 않은 판단이다.
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-12).

## 0. 메인이 정한 것

나중에 바꾸면 스키마나 이미 나간 감사 항목, 계약이 틀어지는 것만 모았다. 동작에 관한 것은 `acceptance.md` 5절에 있다.

| # | 정할 것 | 정한 것 | 까닭 | 버린 대안 |
|---|---|---|---|---|
| 1 | 담당자 표시를 두는 곳 | `company_member`에 칸 `manager boolean NOT NULL DEFAULT false` | D-35가 "소속에 붙는 표시"로 정했다. 소속 행이 지워지면 표시도 같이 사라진다(S2-AC-04) | 표를 따로 두기. 소속을 지울 때 같이 지우는 것을 잊을 수 있다 |
| 2 | 행 ID | `company_member`와 새 표 `membership_request`에 칸 `row_id uuid NOT NULL`(유니크). 값은 DB가 만드는 난수 UUID다. 요청이 승인되면 새 소속 행이 요청의 `row_id`를 물려받는다(S2-AC-34) | 계정 ID는 순서대로 올라 전부터 있던 계정인지 드러낸다(`acceptance.md` 5절 2). 두 표의 ID가 같은 꼴이어야 행의 종류가 갈리지 않는다 | 소속은 계정 ID로, 요청은 요청 번호로 가리키기 |
| 3 | 소속 요청을 두는 곳 | 새 표 `membership_request`. 닫힌 요청도 지우지 않고 상태를 남긴다 | "같은 처리를 다시 하면 200, 반대면 409"(S2-AC-37)를 답하려면 닫힌 요청의 결과가 있어야 한다. 감사 항목이 요청을 가리킨다 | 닫히면 지우기 |
| 4 | 요청에 넣는 이메일과 이름 | 담당자가 넣은 값을 요청 행에 그대로 둔다. 계정의 이름과 이메일은 고치지 않는다 | 담당자의 목록은 담당자가 넣은 값을 보여야 새 계정의 행과 같다(S2-AC-16) | 계정의 실제 이름을 보이기. 다른 고객사에 있는 사람의 이름이 드러난다 |
| 5 | 감사 종류의 이름 | 새 종류 여섯: `MANAGER_MARKED`, `MANAGER_UNMARKED`, `MEMBERSHIP_REQUESTED`, `MEMBERSHIP_REQUEST_WITHDRAWN`, `MEMBERSHIP_REQUEST_APPROVED`, `MEMBERSHIP_REQUEST_REJECTED`. 담당자의 초대, 다시 보내기, 빼기는 있던 종류(`INVITATION_CREATED`, `INVITATION_RESENT`, `INVITATION_CANCELLED`, `CUSTOMER_REMOVED`)를 쓴다 | 종류 이름은 해시에 들어가 나중에 바꿀 수 없다 [소스: `AuditAction`의 머리말]. 누가 했는지는 "한 사람"이 말하므로 담당자용 종류를 따로 두지 않는다 | `MANAGER_INVITED`처럼 담당자용 종류 두기. 같은 일에 이름이 둘이 된다 |
| 6 | 새 결정 기록 | 없다. D-35의 세부다 | 새 설정 키, 새 의존성, 공개 계약의 규칙 변경이 없다 | – |

### 감사 항목의 꼴 (테스트 탭의 질문에 메인이 정함, 2026-10-12)

대상과 바뀐 값은 해시에 들어가므로 나중에 바꿀 수 없다. 있던 소속 항목(`CUSTOMER_ADDED`: 대상 `{type: COMPANY, code, accountId}`, 바뀐 값 `{member: true}`)과 같은 꼴로 둔다.

| 종류 | 대상 | 바뀐 값 |
|---|---|---|
| `MANAGER_MARKED`, `MANAGER_UNMARKED` | `{type: COMPANY, code, accountId}`(지정된 고객) | `{manager: true}` / `{manager: false}` |
| `MEMBERSHIP_REQUESTED` | `{type: COMPANY, code, accountId}`(요청의 대상 계정) | `{email, name}`(담당자가 넣은 값) |
| `MEMBERSHIP_REQUEST_WITHDRAWN`, `…_APPROVED`, `…_REJECTED` | 같다 | `{email}` |

승인하면 `MEMBERSHIP_REQUEST_APPROVED`와 `CUSTOMER_ADDED`가 하나씩 남는다(소속이 생긴 일이라 "소속 더하기"로 걸러도 나와야 한다). 관리자가 소속 더하기로 요청을 닫은 경우(S2-AC-38)도 같은 둘이다. 닫힌 요청에 같은 처리를 다시 하면 아무것도 남지 않는다(S2-AC-42).

대상의 종류를 새로 만들지 않는다. 감사 로그 화면의 대상 필터(고객사, 계정)가 그대로 듣는다.

## 1. 데이터 (마이그레이션 V8)

| 바뀌는 것 | 내용 | 까닭 |
|---|---|---|
| `company_member` | 칸 둘을 더한다: `manager boolean NOT NULL DEFAULT false`, `row_id uuid NOT NULL DEFAULT gen_random_uuid()`(유니크) | 있던 행은 모두 담당자가 아니고 행마다 새 ID를 받는다(S2-AC-51). `gen_random_uuid()`는 PostgreSQL 13부터 확장 없이 있다 [추론: 이미지의 16에서 실험으로 본다] |
| 새 표 `membership_request` | `id bigint`(기본 키), `tenant_id bigint NOT NULL`, `row_id uuid NOT NULL`(유니크), `company_id`, `account_id`(대상 계정), `requested_by`(담당자 계정), `email text`, `name text`(담당자가 넣은 값), `state text`(`PENDING`·`APPROVED`·`REJECTED`·`WITHDRAWN`), `created_at`, `closed_at`, `closed_by` | D-18의 `tenant_id`. 외래 키는 `company`와 `account`로 건다 |
| `membership_request` 색인 | `(company_id, account_id)`에 `state = 'PENDING'`인 행만 거는 유니크 부분 색인. `(state, created_at)` | 같은 고객사에 같은 계정의 걸린 요청은 하나다. 같은 때 온 초대 둘이 요청 둘을 만들지 못한다. 관리자 목록은 오래된 것부터다(S2-AC-33) |
| 알림 종류 | 콘솔 알림의 종류 제약에 `MEMBERSHIP_REQUESTED`를 더한다 | S2-AC-39. 제약이 표에 있는지는 계약 탭이 V3을 보고 맞춘다 [추론] |

- SQL 마이그레이션 하나다. 있는 행의 값을 계산해 채우는 일이 없어(기본값으로 채워진다) 1b의 V7처럼 오래 걸리지 않는다.
- 표가 하나 늘어나므로 `deploy/upgrade/make-sample.py`가 담당자 한 사람과 걸린 소속 요청 하나를 만든다(S2-AC-52). 요청은 API로 만든다: 담당자가 다른 고객사에 이미 있는 이메일을 초대한다.
- 옛 이미지는 이 스키마에서 뜨지 않는다(D-46). 릴리스 노트에 되돌릴 수 없는 마이그레이션으로 적는다(D-24).

## 2. 권한을 보는 곳

- 담당자인지는 세션에 담지 않고 요청마다 DB에서 읽는다 [소스: `SessionFilter`는 계정만 다시 읽고, 소속은 조회 SQL의 `CompanyRepository.SCOPE`가 건다]. 구성원 엔드포인트는 맨 앞에서 "이 계정이 이 고객사의 소속인가, 담당자인가"를 한 문장으로 읽는다: 소속이 아니면 404, 소속이지만 담당자가 아니면 403(S2-AC-09, COM-1·2의 순서).
- 이 확인은 한 곳(서비스의 함수 하나)에 두고 구성원 엔드포인트 넷(목록, 초대, 빼기, 다시 보내기)이 모두 지난다. 엔드포인트마다 따로 쓰지 않는다.
- 쓰기 요청(초대, 빼기, 다시 보내기)은 같은 트랜잭션 안에서 자기 소속 행을 `FOR SHARE`로 잠그고 담당자인지 다시 본다. 확인과 쓰기 사이에 관리자가 지정을 풀면 그 요청은 실패한다(S2-AC-11) [추론: 잠금 순서는 `architecture.md`의 순서에 맞춰 계약 탭이 정한다].
- 행 ID로 가리키는 요청은 "이 고객사의 행인가"를 조회 조건에 넣는다. 다른 고객사의 행 ID는 없는 ID와 같게 404다(S2-AC-32).
- 포털 경로는 `/api/portal/companies/{code}/members…` 아래에 둔다 [추론: 계약 탭이 정한다]. `SecurityConfiguration`은 포털 전체를 고객 역할로만 연다 [소스]. 담당자 확인은 그 뒤 서비스에서 한다.

## 3. 초대의 두 갈래를 같게 만들기

초대 한 번의 흐름이다. 한 트랜잭션이다.

1. 담당자인지 본다(2절). 소속이 아니면 404, 담당자가 아니면 403이다.
2. 입력을 검사한다(S2-AC-19). 횟수(S2-AC-20)는 `conventions.md`대로 로그인 확인 바로 뒤, 권한 확인 앞에서 센다(담당자가 아닌 소속 고객의 초대도 센다. 구현 탭이 규약을 따라 고쳤다). `conventions.md`의 순서(404 → 403 → 400)대로다. 처음 초안은 입력 검사를 앞에 두었는데 계약 탭이 규약과 어긋난다고 짚어 고쳤다.
3. 고객사가 `ARCHIVED`인지(S2-AC-18), 수락 전과 걸린 요청의 합이 100인지(S2-AC-21) 본다.
4. 이메일로 계정을 넣어 본다 [소스: `AccountRepository.insertInvited`는 `on conflict do nothing`이다].
   - 들어갔으면 새 계정이다: 소속 행과 초대 행을 넣고 메일을 대기에 넣는다. 관리자 초대의 서비스 함수(`InvitationService.invite`)를 같이 쓴다.
   - 안 들어갔으면 있는 계정이다: 그 계정이 이 고객사의 소속이거나 걸린 요청이 있으면 409(S2-AC-17). 아니면 요청 행을 넣고 관리자 알림을 넣는다.
5. 두 갈래 모두 본문 없는 같은 상태 코드로 답한다.

- 409 `ALREADY_LISTED`는 담당자의 목록에 이미 보이는 행에만 난다. 목록에 없는 것은 드러내지 않는다.
- 3의 검사가 4보다 앞이라, 한도와 보관 상태의 답이 계정이 있는지에 따라 달라지지 않는다.
- 응답 시간은 같게 만들지 않는다(`premortem.md` 1절). 두 갈래 모두 INSERT 두세 번이고 메일은 대기에 넣기만 한다(보내는 것은 뒤의 작업이다) [소스: `MailQueue`]. 비밀번호 해시 계산처럼 한쪽에만 있는 무거운 일이 없다.
- 구성원 목록은 소속 행과 걸린 요청 행을 한 SQL(`UNION ALL`)로 읽어 이메일로 줄 세운다. 두 갈래를 앱에서 따로 읽어 합치지 않는다. 쪽 나누기가 두 종류를 가르지 않게 하려는 것이다.
- 요청 행의 상태는 늘 `INVITED`다. 대상 계정의 실제 상태(사용 중, 정지)는 승인 전까지 목록에 나오지 않는다.

## 4. 빼기

- 한 트랜잭션에서: 자기 소속 행을 잠그고 → 대상이 자기 자신과 담당자인지 본 뒤(S2-AC-28) → 대상의 소속을 조건을 붙여 지우고(대상 행은 잠그지 않는다. 서로를 빼는 두 담당자의 교착을 피한다, `architecture.md`) → 그 계정이 수락 전이고 남은 소속이 없으면 초대를 취소한다(S2-AC-25). 초대 취소는 관리자의 취소와 같은 함수다 [소스: `InvitationService.cancel`].
- "남은 소속이 없다"는 소속을 지운 뒤에 센다. 걸려 있는 다른 고객사의 소속 요청은 소속이 아니다.
- 감사 항목은 `CUSTOMER_REMOVED` 하나, 초대도 취소했으면 `INVITATION_CANCELLED` 하나가 더 남는다.
- 요청 행을 빼면 요청의 상태만 `WITHDRAWN`으로 바꾼다. 관리자 알림 항목은 지우지 않는다.

## 5. 승인과 거절

- 승인은 콘솔의 소속 더하기와 같은 함수로 소속을 만든다 [소스: `CompanyService.addCustomer`]. 그 함수가 걸린 요청을 찾아 닫게 하면 S2-AC-38(관리자가 다른 길로 소속시킨 경우)도 같은 곳에서 된다. 새 소속 행의 `row_id`는 요청의 것을 쓴다.
- 요청 행을 `FOR UPDATE`로 잠그고 상태를 본 뒤 바꾼다. 관리자 둘이 같은 때 승인과 거절을 눌러도 하나만 된다(S2-AC-37).
- 대상 계정에게는 아무것도 보내지 않는다(S2-AC-40).

## 6. 쓸 컴포넌트와 토큰

- 새 토큰과 새 공용 컴포넌트는 없다(`screens.md` 3절).
- `RowMenu`를 콘솔 앱에서 `@popdesk/ui`로 옮긴다. props는 그대로다.
- `AppShell`의 `NavItem`에 숫자를 보이는 선택 칸을 하나 더한다(콘솔 "고객사"의 걸린 요청 합). 있던 props는 바꾸지 않는다.
- 포털의 `nav`는 지금 빈 배열이다 [소스: `web/portal/src/app/layout.tsx`]. 담당자에게만 항목 둘을 준다.

## 7. 계약 앞 실험

짧다. 계약 탭(C-4 서버)이 껍데기를 쓰기 전에 같이 본다. 따로 실험 탭을 띄우지 않는다.

| # | 볼 것 | 까닭 |
|---|---|---|
| 1 | 이미지의 PostgreSQL 16에서 `gen_random_uuid()`가 확장 없이 되고, 앱의 DB 사용자로 `ALTER TABLE … ADD COLUMN … DEFAULT gen_random_uuid()`가 있던 행마다 다른 값을 넣는지 | 1절 |
| 2 | `project.NoStoreTest`가 새 GET(구성원 목록)을 담당자가 아닌 고객으로 불러 403을 받는지 | 이 테스트는 모든 GET이 2xx이기를 바란다 [소스: `everyReadSucceedsWithNoStore`]. 걸리면 테스트 쪽 탭이 픽스처의 고객을 담당자로 지정하게 고친다(1b의 `format=JSONL`과 같은 일) |

## 8. 일하는 차례

1. C-4 서버 계약: V8 없이 컨트롤러 껍데기와 요청·응답 타입(501). 계약 파일과 생성 클라이언트. 7절의 실험 둘.
2. 계약을 합친 뒤 `NoStoreTest`, `CheckOrderTest`, `EndpointMappingsTest`, `BoundaryListTest`를 돈다(1b C-11 규칙).
3. C-4 웹 껍데기: P-8 화면과 story, C-9·C-8의 바뀌는 곳, `RowMenu` 옮기기.
4. C-5 테스트: 서버 둘(담당자와 구성원, 소속 요청과 감사), 웹 하나, 숨긴 E2E 하나.
5. C-6 실패 확인과 잠금, C-7 점검, C-8 작업 분해.
6. C-9 구현: 서버 하나(V8, 서비스, 견본 생성기), 웹 하나. 서버가 작아 탭을 나누지 않는다.
7. C-10 검증.
