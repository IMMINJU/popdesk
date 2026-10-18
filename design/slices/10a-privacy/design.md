# 10a 묶음 설계

> `acceptance.md`의 기준을 어떻게 만드는지 적는다. 조사는 메인 세션의 서브에이전트가 했다(2026-10-18). [소스]는 지금 코드에서 읽은 것, [추론]은 읽지 않고 짐작한 것이다.
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-18).

## 0. 메인이 정한 것

| # | 정한 것 |
|---|---|
| 1 | 새 서버 패키지 `helpdesk.privacy`: `AccountExportService`(내보내기 흘려 쓰기), `AnonymizationService`(익명화 전체를 이끈다), `PrivacyRepository`(이 패키지가 DB를 읽는 곳). 감사 쪽은 `helpdesk.audit`에 `AuditRechain`(두 단계 재계산, 0절 2)과 순수 계산 `AuditAnonymizer`를 둔다. `privacy` → `account`·`audit`·`photo` 방향만 쓰고 거꾸로는 없다(새 순환 없음, `PackageCyclesTest`) |
| 2 | 재계산(D-62)은 한 트랜잭션 안에서 두 부분이다. ① 사슬 머리도 계정 행도 잠그지 않고, 그 사람의 감사 항목 가운데 가장 작은 `seq`(F)부터 지금 머리(T0)까지를 V7 소급 계산처럼 흘려 읽으며 `AuditAnonymizer`로 값을 바꾸고 새 hash를 이어 계산해 묶음마다 바로 `UPDATE`한다(`unnest` 배열로 묶음 하나에 한 문장, `(values …)`는 바인드 변수 32,767개 한도에 걸린다). 메모리에는 묶음 하나와 앞 hash만 든다. ② 계정 행을 잠그고(0절 7) 검사한 뒤 계정 쪽을 바꾸고, 사슬 머리를 `FOR NO KEY UPDATE`로 잡아 T0 뒤에 붙은 항목만 같은 방법으로 이어 계산하고 머리를 고친다. 그 뒤 `AUDIT_RECHAINED`, `ACCOUNT_ANONYMIZED` 순서로 붙인다. 머리를 잡는 시간은 ①이 아니라 T0 뒤에 붙은 항목 수에 비례한다. ①의 행 잠금은 붙이기와 부딪히지 않는다(붙이기는 옛 행을 건드리지 않는다). 무결성 확인과 내보내기는 스냅샷이라 커밋 전의 사슬을 본다 |
| 3 | 감사 보관 작업(`AuditService.removeOlderThan`)과 익명화는 앱 안의 잠금 하나를 같이 쓴다. 그래서 ① 동안 시작점이 움직이지 않는다. 보관 작업은 익명화가 끝날 때까지 기다린다(하루 한 번 작업이라 늦어도 된다) |
| 3-1 | 붙이기(`AuditChainRepository.append`)는 머리를 잡은 뒤, 붙일 항목의 `actor.accountId`·`target.accountId` 가운데 익명 계정(`anonymized_at` 있음)이 있으면 그 항목을 `AuditAnonymizer`로 바꿔 붙인다. 익명화가 커밋되기 전에 값을 만들어 둔 항목(그 사람의 로그인 실패 따위, `AuditLog.record`가 값을 부를 때 만들고 커밋 직전에 붙인다 [소스: `AuditLog.java:415-419`])이 옛 이름으로 붙지 않게 한다. 조회는 붙일 항목의 계정 ID들로 한 번이다 |
| 4 | `AuditAnonymizer.anonymize(row, accountId, name, email)`: 감사 행의 `actor`가 그 계정이면 `name`·`email`을 바꾸고, `target.accountId`가 그 계정이면 `changes` 맨 위의 `name`·`email`을 바꾼다(글자면 글자로, `{from, to}`면 둘 다). 바뀐 것이 없으면 같은 행을 돌려준다. 순수 계산이라 C-5 뒤 단위 테스트와 PIT를 받는다 |
| 5 | 바뀐 JSON은 hash 전에 DB를 한 번 다녀오게 한다(지금 붙이기와 같은 규칙, `architecture.md` 감사 절) [소스: `AuditChainRepository.append`]. ①에서는 `jsonb_set`으로 DB가 바꾼 값을 읽어 오는 쪽이 간단하다. 어느 쪽이든 붙이기·소급 계산·확인이 같은 `AuditChain` 함수를 지난다 |
| 6 | 익명화 한 번에 하나: 0절 3의 앱 안 잠금을 `tryLock`으로 잡고 못 잡으면 409(무결성 확인의 `AtomicBoolean`과 같은 뜻) [소스: `AuditService.verify`]. 보관 작업은 같은 잠금을 기다려 잡는다. 앱 인스턴스는 하나다(D-23) |
| 7 | 계정 쪽은 `AccountService.changeRoleOrSuspend`(private)의 잠금과 순서를 따른다: `accounts.lockWithActiveAdmins(id)` → 익명 검사(`ACCOUNT_ANONYMIZED`가 다른 검사보다 먼저) → 자기·마지막 관리자 검사 → 바꾸기 → 담당 비우기(고르면) → 감사 → `Sessions.endAll` [소스: `AccountService.java:170-229`]. `account` 패키지에 공개 메서드 `AccountService.anonymize(admin, id, clearAssignments, AnonymousValues)`를 두고 `privacy`가 부른다. `AnonymousValues`는 `account`에 둔다(`privacy`에 두면 `account → privacy` 순환). 비밀번호는 `null`이 아니라 `PasswordHashes.RESET`으로 바꾼다(`null`이면 수락 전 계정으로 보여 정지가 400이 된다 [소스: `AccountService.java:183-185`]) |
| 7-1 | 다른 모듈의 행은 그 모듈이 지운다: 메일 대기 행과 알림함 항목은 `notification` 쪽 API(`MailQueue`에 받는 계정으로 지우기, 알림함에 계정으로 지우기)를 더해 부른다(지금 `MailQueue.dropAbout`과 같은 꼴). 사진은 `photo`, 세션은 `account.Sessions`, 소속 요청은 `company`가 맡는다 |
| 8 | 익명 이름은 `InstallationLocale`에서 고른다 [소스: `InstallationLocale`가 `en`·`ko`]. 서버 문구는 지금 메일 문구처럼 서버 쪽 글 묶음에 둔다 |
| 9 | 내보내기는 `StreamingResponseBody`로 읽기 전용 `REPEATABLE READ` 하나에서 Jackson `JsonGenerator`로 흘려 쓴다(감사 내보내기와 같은 꼴) [소스: `ConsoleAuditController` 내보내기]. 동시 둘은 감사 내보내기와 따로 센 세마포어, 넘으면 503 `SERVER_BUSY`와 `Retry-After`. `HEAD`도 둔다(화면이 먼저 확인한다) |
| 10 | 웹 계약 탭은 따로 두지 않는다. 공용 컴포넌트 API를 바꾸지 않는다: `ConfirmDialog`에는 막힌 확인 단추와 입력 칸 자리가 없으므로, 익명화 대화상자는 C-10 안에서 `Dialog`와 폼으로 만든다(`RenameDialog`와 같은 꼴, 확인 단추는 `Button blocked`, 열 때 초점은 이메일 칸, Enter로 보냄). 이메일 맞춤은 순수 함수 `matchesEmail(typed, email)`로 옆 모듈에 둔다(Stryker 대상) |
| 11 | 내보내기 확인(`HEAD` → 상태별 문구 → `window.location.assign`)을 `C12AuditLog.tsx`에서 `console/src/app`의 도움 함수로 옮겨 C-10과 C-12가 같이 쓴다. 503은 `Retry-After`의 초를 문구에 넣고, 401은 로그인으로 보낸다(지금 C-12와 같음). 두 번 누름을 막는다 |
| 12 | C-12 종류 묶음: `ACCOUNT_EXPORTED`는 "내보내기"(지금 `AUDIT_EXPORTED`가 있는 묶음 [소스: `auditWords.ts:73`]), `ACCOUNT_ANONYMIZED`와 `AUDIT_RECHAINED`는 "계정". 바뀐 값 이름: `fromSeq` "시작 번호", `toSeq` "끝 번호", `headHash` "마지막 hash", `clearedIssues` "비운 담당 문의" |

## 1. 표 (V22)

```sql
-- 익명화한 때(PII-4). 익명 계정만 값이 있다. 되살리는 길을 막는 데 쓴다(acceptance 5절 4).
ALTER TABLE account ADD COLUMN anonymized_at timestamptz;
```

- 새 표가 없다. `make-sample.py`는 익명 계정 하나를 만든다(S10A-AC-34).
- `TimeTest`(시각 칸은 `timestamptz`)를 지킨다. 서버 구현 탭은 `start` 패키지를 돈다.

## 2. 계약 (C-4)

콘솔 계약만 바뀐다.

| 경로 | 요청 | 응답 | 오류 |
|---|---|---|---|
| `GET`·`HEAD /api/console/accounts/{accountId}/export` | – | 200 `application/json` 파일(3절 꼴) | 401, 403, 404, 503 `SERVER_BUSY` |
| `POST /api/console/accounts/{accountId}/anonymize?clearAssignments=` | – | 200 `SuspendedAccount`와 같은 꼴 | 400 `OWN_ACCOUNT`·`LAST_ADMIN`·`ACCOUNT_ANONYMIZED`, 401, 403, 404, 409 `ANONYMIZATION_RUNNING` |

- 계정 응답(`Account`, `SuspendedAccount`, 목록 줄)에 `anonymized: boolean`(필수)을 더한다.
- 익명 계정에 대한 이름 고치기, 역할, 정지 해제, 비밀번호 초기화, 초대 다시 보내기, 초대 링크 복사의 400 설명에 `ACCOUNT_ANONYMIZED`를 더한다.
- 감사 종류(`AuditAction`) 셋: `ACCOUNT_EXPORTED`, `ACCOUNT_ANONYMIZED`, `AUDIT_RECHAINED`. 대상 종류 `AUDIT_LOG`는 이미 있다 [소스: `AuditTargetType.java:8`]. C-12 종류 묶음은 0절 12.
- 계약 뒤 한 번 돌 것: `project.NoStoreTest`, `ops.CheckOrderTest`, `ops.EndpointMappingsTest`, `boundary.BoundaryListTest`, `boundary.AdminOnlyContractTest`.

## 3. 내보내기 파일

```json
{
  "exportedAt": "2026-10-18T03:00:00.000000Z",
  "account": {"id": 12, "email": "…", "name": "…", "role": "CUSTOMER", "status": "ACTIVE",
              "locale": "ko", "createdAt": "…", "anonymized": false},
  "photo": {"contentType": "image/png", "base64": "…"},
  "issues": [{"key": "ACME-7", "companyCode": "ACME", "title": "…", "content": "…", "status": "CLOSED",
              "public": true, "createdAt": "…", "closedAt": "…", "deletedAt": null}],
  "comments": [{"issueKey": "ACME-7", "content": "…", "internal": false, "createdAt": "…", "editedAt": null}],
  "attachments": [{"issueKey": "ACME-7", "commentId": null, "fileName": "a.png", "sizeBytes": 1024,
                   "createdAt": "…"}]
}
```

- 시각은 `conventions.md`의 꼴이다. 칸 순서는 위와 같다. 배열은 시각 순, 같으면 ID 순.
- "그 계정이 올린 문의"는 `issue.reporter_id`가 그 계정인 것이다. 대리 등록한 상담원(`created_by`)의 내보내기에는 그 문의가 들지 않는다 [소스: 서버 검토].
- 다른 계정 값은 넣지 않는다: 담당자, 대리 등록한 사람, 댓글의 멘션 대상 ID, 고객사 이름(코드만).

## 4. 익명화 순서 (한 요청)

1. 앱 안의 잠금(0절 3)을 `tryLock`으로 잡는다(못 잡으면 409).
2. 계정을 읽어 없으면 404, 이미 익명이면 400(잠금 전 빠른 거절. 잠근 뒤 다시 본다).
3. 트랜잭션 시작:
   1. 재계산 ①(머리도 계정 행도 잠그지 않음, 0절 2).
   2. `lockWithActiveAdmins` → 익명·자기·마지막 관리자 검사(400이면 ①까지 통째로 되돌아간다).
   3. `account` 갱신(이름, 이메일, 상태, `anonymized_at`, 비밀번호 `PasswordHashes.RESET`, 실패 횟수 0, 잠금 없음, `updated_at`).
   4. 지우기(각 패키지의 API로, 0절 7-1): 사진, 초대, 비밀번호 재설정, 메일 대기 행(받는 계정), 알림함(그 계정의 것).
   5. `membership_request`의 `email`·`name`을 익명 값으로.
   6. 담당 비우기(고르면).
   7. 재계산 ②, 그 뒤 감사 둘.
   8. 세션 끝내기(`Sessions.endAll`).
4. 앱 안의 잠금을 놓는다.

- 2~3 사이에 다른 관리자가 그 사람을 정지해도 상관없다(①은 감사 값만 본다). 그 사이에 그 사람의 새 감사 항목(로그인 실패 따위)이 붙으면 ②의 이어 계산이 그 항목도 `AuditAnonymizer`로 바꾼다.
- 세션 지우기는 커밋 뒤에도 그 계정의 요청이 하나 남아 돌 수 있다 [추론: 정지와 같다. 지금 정지 테스트가 받아들이는 범위].

## 5. 감사 바깥 사본과 확인 스크립트

- 보내기 대기의 감사 사본은 보낼 때 `audit_log`에서 본문을 읽으므로 [소스: `AuditCopyBodies.body`] 따로 바꿀 것이 없다. `AUDIT_RECHAINED`도 보통 항목처럼 사본 행이 생긴다.
- `docs/integrate/audit-verify.py`: S10A-AC-24의 구간 나누기. 결과 줄에 `rechained F-T at R`을 적는다. `AuditVerifyScriptTest`에 경우를 더한다(테스트 쪽).
- `docs/integrate/conventions.md`의 감사 절에 `AUDIT_RECHAINED`의 꼴과 "새 감사 종류가 다른 사람을 `changes`에 넣을 때는 `{accountId, name, email}`"을 적는다.

## 6. 웹

- `C10MemberList.tsx`: 줄 메뉴(screens.md), 익명화 대화상자, "익명화됨" 배지. 내보내기는 `C12AuditLog.tsx`의 `HEAD` 뒤 `window.location.assign` 꼴을 따른다 [소스].
- 담당 수 조각은 정지 대화상자의 것을 그대로 쓴다 [소스: `ClearAssignments`].
- 가짜 서버(`console.ts`)의 계정 견본에 `anonymized: false`를 더한다(계정 응답은 계약 검증을 받지 않는다. 빠지면 `account()` 견본의 타입 검사가 실패한다 [소스: 웹 검토]).

## 7. 테스트 쓰는 법 (C-5)

- 서버 HTTP 테스트는 계정을 API로 만든다(초대 → 수락). 감사 사슬은 설치에 하나라 다른 테스트의 항목이 섞인다. 재계산 테스트는 자기 계정 ID로 거른 항목만 단언하고, 무결성 확인은 "맞다"만 본다. 재계산 동시성(S10A-AC-20~22)은 따로 띄운 앱 하나에서 돈다(8a SLA 테스트처럼).
- S10A-AC-20의 "5초 넘게 막지 않는다"는 감사 10만 건을 넣은 따로 띄운 앱에서 익명화와 로그인 20번을 같이 보내 로그인이 모두 성공하는지로 본다. 10만 건은 SQL로 넣는다(스키마 검사처럼 DB를 봐야 하는 예외. 넣은 뒤 `AuditChain`과 같은 규칙의 소급 계산은 테스트 도움이 하지 않고, 앱을 처음 띄우기 전에 넣어 V7과 같은 길을 타게 하는 방법을 테스트 쪽이 고른다 [추론: 질문 목록에 올 수 있다]).
- 앱이 재계산 중에 죽는 경우(S10A-AC-19 뒷부분)는 DB 연결을 끊어 트랜잭션이 되돌아가게 하는 꼴로 본다(Testcontainers의 Postgres에서 그 백엔드를 `pg_terminate_backend`) [추론: 테스트 쪽이 고른다].
- 바깥 사본은 테스트용 받는 쪽(1b에 있음)으로 본다.
- 웹은 C-10 테스트 파일에 경우를 더한다. "닫힌 뒤에 온 답"을 버리는 테스트를 익명화 대화상자에 둔다.
