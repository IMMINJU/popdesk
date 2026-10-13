# 5번 묶음 설계

> 수용 기준(`acceptance.md`)을 어떻게 만들지 가운데, 나중에 바꾸기 어려운 것만 적는다. API 경로와 응답 모양은 계약 단계(C-4)의 컨트롤러 껍데기가 정한다.
> 표시: [소스]는 레포에서 본 것, [추론]은 확인하지 않은 판단이다.
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-13).

## 0. 메인이 정한 것

| # | 정할 것 | 정한 것 | 까닭 | 버린 대안 |
|---|---|---|---|---|
| 1 | 마이그레이션 | `V13`: `attachment`에 `inline boolean NOT NULL DEFAULT false`. `attachment_stored_has_issue_check`를 `state <> 'stored' OR issue_id IS NOT NULL OR inline`으로 바꾼다. 부분 색인 `(uploaded_by) WHERE inline AND issue_id IS NULL` | 임시 이미지는 파일이 다 쓰인(`stored`) 채 문의가 없다. 지금 제약은 그것을 막는다 [소스: V4]. "묶였는가"는 `issue_id`가 말하고 "만료됐는가"는 `created_at`이 말하므로 칸은 하나면 된다 | 새 표(`temporary_image`): 묶을 때 행을 옮겨야 하고 ID가 두 표에 걸친다 / 새 상태값: ATT-10의 네 상태는 파일의 상태다 |
| 2 | 임시 이미지의 경로 | 앱마다 올리기 하나, 읽기 하나(`/api/{앱}/images`, `/api/{앱}/images/{id}`). 묶인 뒤에는 지금의 `/issues/{key}/attachments/{id}`만 | 묶인 이미지의 권한은 문의의 권한이고 그 쿼리가 이미 있다 [소스: `conventions.md` "Attachment paths"]. 임시 이미지의 권한은 "올린 사람"뿐이라 쿼리가 다르다 | 한 경로로 둘 다 읽기(권한 갈래가 한 쿼리에 섞인다) |
| 3 | 묶는 ID를 싣는 칸 | 글을 쓰는 요청 본문의 배열 칸 하나(등록, 대리 등록, 문의 고치기, 댓글 달기, 댓글 고치기). 없거나 비면 지금과 같다 | 지금 요청에 그런 칸이 없다 [소스: `CreateIssueRequest`] | 따로 부르는 묶기 요청(글과 한 트랜잭션이 안 된다) |
| 4 | 이력의 꼴 | `kind`는 `CHANGED` 그대로, `field`는 새 이름 `ATTACHMENT`. 붙으면 `newValue`에 첨부 ID, 지워지면 `oldValue`에 첨부 ID | `kind`는 닫힌 값이고 DB 제약도 둘뿐이다. `field`는 "뒤 릴리스가 이름을 더한다"고 계약에 적힌 열린 글자다 [소스: V9:35·43, `openapi-console.json`의 `IssueHistoryEntry`]. 마이그레이션과 계약의 닫힌 값을 건드리지 않는다 | 새 `kind` 둘(`ATTACHED`, `DETACHED`): 제약을 고치고, 닫힌 enum에 값을 더하면 옛 클라이언트가 깨진다 |
| 5 | 정리 작업 | 새 `CleanupJob` 하나(`temporary-image-cleanup`, 10분) | 있는 틀이 간격마다 돌리고 기록을 남긴다 [소스: `Housekeeping`, `AttachmentCleanup`]. 하루 한 번인 첨부 정리와 간격이 다르다 | 첨부 정리 작업에 넣기 |
| 6 | 형식 가르기 | 앞 바이트만 보는 순수 계산 클래스 하나. PNG, JPEG, GIF(87a·89a), WebP(`RIFF`…`WEBP`). SVG는 받지 않는다 | SVG는 스크립트를 담는다(TM-19). 프로필 사진의 머리글 읽기가 본보기다 [추론: 그 클래스를 다시 쓸 수 있는지는 구현 탭이 본다] | 확장자, 요청의 `Content-Type` |
| 7 | 패키지 방향 | `attachment` → `comment` → `issue`, `attachment` → `issue`. `issue`와 `comment`는 `attachment`를 모른다(4절) | 지금 `comment` ↔ `attachment`가 순환이다 [소스: `package-cycles.txt`]. 이 묶음이 `issue`·`comment`에서 묶기를 부르면 순환이 하나 더 생긴다 | 한 패키지로 합치기 |
| 8 | 웹의 자리 | 그리기와 편집기의 이미지 갈래는 `@popdesk/ui`(주소와 올리기는 인자), 올린 ID를 기억하고 보낼 ID를 고르는 훅은 `@popdesk/app`, 경로를 주는 한 줄은 앱 | D-57. 두 앱이 같은 것을 쓴다 | 앱마다 쓰기 |

## 1. 임시 이미지

- 올리기는 지금 첨부의 세 걸음을 그대로 탄다: ① `pending` 행을 따로 커밋(`inline = true`, `issue_id` 없음), ② 저장소에 쓰기, ③ `stored`로 [소스: `AttachmentService.writePending`, S0-AC-56]. ③은 문의가 없으니 문의 행을 잠그지 않는 조건부 UPDATE다.
- 20개·50MB(S5-AC-05)는 잠그지 않고 센다. 한 사람이 동시에 올려 한두 개 넘는 것은 받아들인다(팀 총량과 같은 태도 [소스: `architecture.md` 잠금 순서 절]).
- 읽기 쿼리의 조건: `id = ? AND uploaded_by = 호출한 사람 AND inline AND issue_id IS NULL AND state = 'stored' AND created_at > 지금 - 24시간`. 하나라도 어긋나면 같은 404다.
- 첨부 정리 작업(하루 한 번)의 "1시간 지난 `pending`"은 임시 이미지의 `pending`도 치운다 [추론: 지금 쿼리가 `inline`을 모르니 그대로 걸린다. 구현 탭이 확인한다].
- 동시 16건 필터가 새 경로도 세게 한다 [소스: `TransferLimitFilter`가 경로를 꼴로 고른다].

## 2. 묶기

- 한 문장이다: `UPDATE attachment SET issue_id = ?, comment_id = ? WHERE id = ANY(?) AND uploaded_by = ? AND inline AND issue_id IS NULL AND state = 'stored' AND created_at > ?`. 바뀐 행 수가 보낸 ID 수(겹친 것을 뺀)와 다르면 트랜잭션을 되돌리고 400이다(ATT-7).
- 자리는 잠금 순서의 2다(문의 행 잠금 뒤, INSERT 앞). 여러 행은 ID 오름차순으로 잡히게 한다 [소스: `architecture.md` 순서 2]. 댓글 달기는 댓글 행이 먼저 있어야 `comment_id`를 적을 수 있다: 댓글 INSERT(3)를 한 뒤 묶으면 순서가 3 → 2가 된다. **댓글의 ID를 먼저 받아 두고(시퀀스) 묶은 뒤 댓글을 넣는다. 첨부의 댓글 외래 키는 지금 미뤄지지 않으므로 [소스: V2 `comment_id bigint REFERENCES comment`], `V13`에서 그 키를 `DEFERRABLE INITIALLY DEFERRED`로 바꾼다**(문의 키가 이미 그렇다 [소스: V2]).
- 50MB(S5-AC-23)는 문의 행을 잠근 뒤 센다.
- 정리 작업의 지우기는 `WHERE inline AND issue_id IS NULL AND created_at <= ?` 조건부다. 묶기와 겨루면 행 잠금이 하나만 이기게 한다(S5-AC-32).
- 잠금 순서 표(`architecture.md`)에 더할 줄: 문의 고치기·댓글 달기·댓글 고치기의 2번 칸에 "묶는 이미지, ID순", 새 줄 "임시 이미지 정리: 문의 행을 잠그지 않는다, 조건부 UPDATE". 구현이 합쳐질 때 메인이 더한다.

## 3. 이력

- 첨부가 붙는 곳(올리기의 ③, 묶기)과 지워지는 곳(지우기의 `purging`, 댓글 지우기의 `purgeOfComment`)에서 같은 트랜잭션에 넣는다. 첨부 지우기는 지금 문의 행을 잠그지 않는다 [소스: 잠금 순서 표 마지막 줄]. 이력 INSERT는 문의 행에 `FOR KEY SHARE`만 걸므로 그대로 둔다.
- 등록의 `storeFiled`는 이력을 남기지 않는다(S5-AC-42).

## 4. 패키지

- `issue` 패키지에 문 하나(인터페이스)를 둔다: 이미지 묶기, 댓글의 첨부 지우기, 커밋 뒤 파일 지우기. `attachment`가 구현한다. `IssueService`와 `CommentService`는 이 인터페이스만 안다.
- `AttachmentService`가 댓글을 찾는 것(`CommentRepository`)은 남는다. `attachment` → `comment` 한 방향이 된다.
- 끝나면 `package-cycles.txt`에서 `attachment`·`comment` 두 줄을 지운다(메인이 지운다. 기준선은 구현 쪽에 잠겨 있다).
- 구현의 첫 일이다: 묶기를 넣기 전에 이 문부터 만들고 `PackageCyclesTest`를 돈다.

## 5. 웹

- 마크다운: 이미지 갈래 하나. `![설명](attachment:{UUID})`만 `<img>`가 되고 주소는 인자로 받은 함수가 준다. 함수가 없거나 `null`을 주면 지금처럼 글자다. 그 밖의 주소는 손대지 않는다 [소스: `markdown.tsx`의 `SAFE_URL`은 링크만 본다].
- `<img>`는 `loading="lazy"`, 폭은 CSS로 묶는다(`.pd-markdown img`). 읽기 실패는 `onError`로 자리 글을 보인다.
- 편집기: 이미지 단추(도구 열째), 붙여 넣기, 끌어다 놓기. 셋 다 같은 함수 하나로 간다. 올리는 중과 실패는 편집기 아래 한 줄이다. 지금 편집기에는 붙여 넣기와 끌어다 놓기 처리가 없다 [소스: `MarkdownEditor.tsx`].
- 주소 고르기: 이 편집에서 올린 ID는 임시 경로, 그 밖은 문의의 첨부 경로. 저장이 성공하면 그 ID들은 묶인 것이 된다.
- 내려받기 머리글(`Content-Disposition: attachment`)이 있어도 `<img>`는 그린다(6절 1에서 Chrome으로 봤다).

## 6. 계약 앞에 볼 것

| # | 볼 것 | 어떻게 | 결과에 따라 |
|---|---|---|---|
| 1 | `Content-Disposition: attachment`와 `nosniff`로 내려온 PNG가 `<img>`로 그려지는가 | **봤다(2026-10-13)**: 그려진다. 그 머리글 셋과 `image/png`로 2×3 PNG를 주는 작은 서버를 띄워 Chrome 155의 `<img>`에 넣었고 `naturalWidth` 2, `naturalHeight` 3으로 읽혔다. WebP와 다른 브라우저는 보지 않았다 | 기준 그대로. 구현 뒤 브라우저 확인에서 진짜 경로로 다시 본다 |
| 2 | 메일과 웹훅이 글의 본문을 싣는가 | **봤다(2026-10-13)**: 싣지 않는다. `MailText`의 문의 메일은 제목만 싣고 [소스: `MailText.java:70`], `notification` 패키지에 글의 `content`를 읽는 곳이 없다(`grep` 0건) | 할 일 없음. `acceptance.md` 5절 12는 닫는다 |
| 3 | 문의 고치기가 문의 행을 잠그는가 | **봤다**: 잠근다. `IssueService`의 쓰기 셋과 `CommentService`의 달기·고치기·지우기가 `issues.lockByKey`로 시작한다 [소스: `IssueService.java:415·433·518`, `CommentService.java:104·156·215`] | 표와 같다. 묶기는 그 뒤에 온다 |
| 4 | 팀 총량이 어떤 상태의 행을 세는가 | **봤다**: `state = 'stored'`인 모든 행 [소스: `AttachmentRepository.storedBytes`] | 임시 이미지는 `stored`라 그대로 세어진다(S5-AC-06). `pending`인 동안은 안 세어진다(지금 첨부와 같다) |
| 5 | 정리 작업 이름을 세는 잠긴 테스트 | **봤다**: 다섯 파일이 `attachment-cleanup`을 이름으로 쓴다. `PasswordResetCleanupTest`와 `IdempotencyKeyTest`는 "먼저 있던 작업 셋이 아닌 작업의 기록"으로 자기 작업을 찾는다 [소스: 두 파일의 `EARLIER_JOBS`] | 새 작업의 기록이 그 테스트들에게 "자기 작업"으로 보일 수 있다(`premortem.md` 5절). 계약 뒤 테스트 탭이 두 파일의 목록에 새 이름을 더한다 |

## 7. 일하는 차례

1. 계약 앞에 볼 것 다섯(메인, 1번만 이미지를 띄운다).
2. 화면 상태 목록(`screens.md`): 편집기와 그리기의 잠긴 테스트를 먼저 읽는다.
3. 계약(C-4): 컨트롤러 껍데기, 오류 코드, `conventions.md`, 두 계약 파일과 클라이언트.
4. 테스트(C-5): 서버 탭 하나(임시 이미지, 묶기, 이력, 정리, 마이그레이션), 웹 탭 하나(`@popdesk/ui`와 `@popdesk/app`, 두 앱 화면, 넘긴 일의 도움 둘), 숨긴 E2E.
5. 실패 확인과 잠금(C-6), 점검(C-7, 서브에이전트로 시험).
6. 구현(C-9): 서버 탭 하나(패키지의 문 → 마이그레이션 → 임시 이미지 → 묶기 → 이력 → 정리 → 견본 생성기). 웹은 공용 탭이 `@popdesk/ui`·`@popdesk/app`을 먼저 만들고 두 앱을 잇는다(화면이 얇아 한 탭으로 한다).
7. 검증(C-10): `local-ci.sh`, 숨긴 E2E, 변이 테스트(순수 계산 셋과 넘긴 일의 파일들), 브라우저. `verify.md`에 "CI 없이 이 PC에서 본 것"과 단계마다 걸린 시간을 적는다.
