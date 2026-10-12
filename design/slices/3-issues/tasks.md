# 3번 묶음 작업 목록 (C-8)

> 한 세션 크기의 작업이다. 작업 하나는 "이 테스트들을 통과시킨다"로 적는다. 묶음이 끝나면 이 파일은 지운다.
> 서버 구현 탭은 셋이고 한 번에 하나씩 차례로 돈다(Maven과 Docker를 같이 쓰지 못한다). 웹 탭 둘(콘솔과 ui, 포털)은 계약만 있으면 되므로 서버 탭과 같은 때 하나씩 돈다. 탭이 합쳐지면 메인이 `pending.txt`에서 그 탭의 줄을 지운다.
> 푸시는 다 합치고 이 PC에서 서버 전체가 초록인 뒤 한 번 한다(`process.md`의 CI 규칙).

## 1. 서버

| 탭 | 맡는 것 | 통과시킬 테스트 |
|---|---|---|
| `s3-i-core` | 마이그레이션 V9(확장, `issue`의 칸 다섯, `issue_history`와 있던 문의의 `CREATED`, `issue_idempotency`, 색인, 계정 검색 색인). 의존성 `commonmark` 0.30.0과 화면 글자 뽑기, 등록과 고치기가 `search_text`를 채우는 것, V10(있던 문의 채우기)과 V11(검색 색인). 상담 쪽 상태 변경과 전이 표, 고객의 종료, 재오픈, 해결·종료 시각, `nextStatuses`와 `reopenable`. 고치기와 `version`. 이력(등록의 `CREATED`, 상태·제목·내용·우선순위의 `CHANGED`)과 이력 목록. 상태의 웹훅 셋 | `issue.IssueStatusTest`, `IssueStatusRaceTest`, `IssueCloseRaceTest`, `IssueCloseReopenTest`, `IssueEditTest`, `start.TrigramExtensionTest`. 그리고 `IssueHistoryTest`, `notify`의 웹훅 클래스 넷, `ops.IssueSearchLogTest` 가운데 담당자와 대리 등록에 기대지 않는 메서드 |
| `s3-i-people` | 담당자 정하기와 비우기, 자격, 후보 목록, "담당자가 볼 수 없음" 표시, 담당자 이력과 웹훅. 열린 담당 수와 담당 비우기(배정 빼기, 정지), 감사 값. 대리 등록(자격, 받은 경로, 256 KB, 요청 수 제한), 만든 사람과 포털의 표시, 대리 등록 메일, 올릴 수 있는 고객 목록 | `issue.IssueAssigneeTest`, `ClearAssignmentsTest`, `OnBehalfFilingTest`, `CompanyReportersTest`, `audit.ClearAssignmentsAuditTest`, `boundary.OnBehalfIdentityTest`, `boundary.IssueBoundaryTest`, `notify.OnBehalfMailTest`, `KoreanOnBehalfMailTest`, `ops.CheckOrderTest`, `project.NoStoreTest`. 앞 탭이 남긴 `IssueHistoryTest`와 웹훅 클래스의 나머지 |
| `s3-i-list` | 두 목록의 필터와 검색, 콘솔의 모르는 쿼리 이름 400, 검색 요청 수 제한(세 목록 합쳐), 검색어가 있을 때 색인으로 먼저 좁히는 쿼리. 등록 키와 정리 작업. `popdesk restore`의 `--no-comments`. 업그레이드 견본 생성기. 문서. 문의 10만 건에서 검색 시간 재기 | `issue.IssueFilterTest`, `PortalIssueFilterTest`, `IssueSearchTest`, `SearchRateLimitTest`, `IdempotencyKeyTest`, `ops.IssueSearchLogTest`의 나머지, `deploy.IssueUpgradeTest`, `RestoreForeignExtensionTest`, S3-AC-90의 견본 테스트. `pending.txt`에 남은 `server` 줄 전부다 |

- 첫 탭은 시작할 때 `pending.txt`의 `server` 클래스 전부를 한 번 돌려(4분쯤) 지금의 실패를 본다. 맡은 클래스 가운데 다른 탭의 코드가 있어야 통과하는 메서드를 찾아 보고에 적는다(첫 묶음 C-11 규칙). 표의 나눔은 메인이 테스트 이름만 보고 한 것이라 틀릴 수 있다. 탭 사이에 넘길 메서드는 보고의 "뒤 탭이 통과시킬 것"에 적는다.
- 스키마는 첫 탭이 다 만든다(V9에 뒤 탭이 쓸 칸과 표까지). 뒤 탭이 칸이 더 필요하면 아직 병합되지 않은 V9에 넣는다. 묶음이 끝날 때 마이그레이션은 V9, V10, V11 셋이다.
- 계약 탭의 실험 결과(`C:\Users\minju\.claude\popdesk\tasks\s3-c4-server-report.md` 4절): `pg_trgm`의 권한과 SQLSTATE(실험 1), 복원과 `--no-comments`(실험 2), `commonmark`가 남기는 글자(실험 3), 색인을 뒤에 만들어야 빠른 것과 검색 쿼리의 계획(실험 4).
- `commonmark`는 0.30.0으로 고정한다. 표와 취소선 확장은 넣지 않는다. HTML을 만드는 렌더러를 쓰지 않는다. 내용 안의 HTML은 적힌 그대로 글자에 넣는다(`acceptance.md` 5절 16).
- V10은 앱의 서비스 클래스를 부르지 않고 마이그레이션 안에 글자 뽑기를 둔다. 앱과 같은 결과를 내는지 단위 테스트 하나가 견준다(`design.md` 1절).
- 확장을 만들 수 없으면 뜨지 않고, 로그 한 줄에 `CREATE EXTENSION` 문장과 DB 이름을 함께 남긴다(S3-AC-91. 테스트가 그 둘이 한 줄에 있는지 본다).
- 같은 때 온 상태 변경은 다시 읽어 다시 판정한다(`design.md` 2절 ④). 닫힌 문의의 고치기는 `ISSUE_CLOSED`가 먼저다.
- `CREATED` 이력 행은 `field`와 두 값이 `null`이다. 제목을 넣지 않는다.
- 문서(`s3-i-list`): `docs/use/permissions.md`의 문의 절(대리 등록의 고객 목록, 담당 비우기, 닫힌 문의의 담당자), `docs/integrate/conventions.md`의 "Rate limits" 검색 줄(세 목록을 합쳐 센다)과 계약 탭이 쓴 절이 구현과 맞는지, `docs/operate/upgrade.md`에 V9~V11(되돌릴 수 없다, `pg_trgm`이 필요하다, 문의 10만 건에 걸리는 시간, DB가 커진다), `docs/operate/`의 바깥 DB 안내(확장을 만들 권한, ctype이 `C`이면 한국어 검색이 느리다, 검색어가 주소에 실려 프록시 로그에 남는다), `docs/operate/configuration.md`에 바뀌는 것이 있으면, `deploy/upgrade/README.md`의 견본 설명.
- deploy 태그 테스트는 마지막 탭이 이미지를 한 번 빌드해 `IssueUpgradeTest`, `RestoreForeignExtensionTest`, 견본 테스트, `UpgradeHarnessTest`만 돈다.

## 2. 웹

| 탭 | 맡는 것 | 통과시킬 테스트 파일 |
|---|---|---|
| `s3-i-web-console` | 공용 `SearchField`(지연, Enter, 지우기, 밖에서 바뀐 값). 콘솔 C-3(보기 고르기, 필터 줄, 담당자 칸, "대리 등록" 버튼, 주소의 쿼리), `/`와 옆 메뉴의 "내 담당" 주소. C-19 대리 등록. C-4(머리의 줄, 담당자 대화상자, 동작 줄, 고치기와 409, 이력, 올린 사람 링크). C-9 배정 빼기와 C-10 정지의 담당 수와 체크 상자, C-10 필터를 주소에, 검색 입력을 `SearchField`로. C-12의 "비운 담당" | `pending.txt`의 `web` 줄 가운데 `ui`와 `console`의 것: `SearchField`, `C3IssueList.filters`, `C19IssueCreate`, `C4IssueDetail.actions`, `C4IssueDetail.edit`, `C9CompanyDetail.clear`, `C10MemberList.clear`, `C12AuditLog.cleared`, 그리고 요구가 바뀌어 고친 잠긴 둘(`App.test.tsx`, `C3IssueList.test.tsx`) |
| `s3-i-web-portal` | P-3(필터 줄, 좁은 화면의 펼침, "확인이 필요합니다", "(지원팀이 대신 올림)"), P-5(머리, 해결 띠, 동작, 고치기와 409), P-4의 등록 키 | `pending.txt`의 `web` 줄 가운데 `portal`의 것: `P3IssueList.filters`, `P5IssueDetail.actions`, `P5IssueDetail.edit`, `P4IssueCreate.key` |

- 포털 탭은 콘솔 탭이 끝난 뒤에 띄운다. `SearchField`를 콘솔 탭이 채운다.
- 브라우저에서 눌러 보는 것은 서버가 합쳐진 뒤 메인이 C-10에서 한다(`screens.md` 4절).

## 3. 구현이 받쳐야 하는 가정

테스트가 기준을 어떻게 읽었는지는 테스트 코드가 가장 정확하다. 탭 보고의 가정 절에 모아 두었다.

| 탭 | 읽을 보고(`C:\Users\minju\.claude\popdesk\tasks\`) |
|---|---|
| 서버 셋 | `s3-c4-server-report.md`의 1절, 2절, 4절, 6절, 7절, 9절. `s3-t-list-report.md`, `s3-t-edit-report.md`, `s3-t-flow-report.md`의 "정한 것"과 "운영 코드가 받쳐 줘야 하는 가정"(자기가 맡은 기준의 줄). `s3-tfix-server-report.md`의 "테스트가 구현에 바라게 된 것". `s3-c7-server-report.md`의 "고쳐야 함" 표의 "통과하는 틀린 구현" 칸(하지 말아야 할 것의 목록이다) |
| 웹 둘 | `s3-c4-web-report.md` 전부(짧다). `s3-t-web-console-report.md`와 `s3-t-web-portal-report.md`의 "운영 코드가 받쳐 줘야 하는 가정"과 "둘째 손질". `s3-tfix-web-report.md`의 "테스트가 구현에 바라게 된 것". `s3-c7-web-report.md`의 "구현하면 깨질 잠긴 테스트", "구현하기에 달린 것", "메인이 볼 것"의 "구현 탭에 알릴 것" |

계약과 설계에서 정한 것은 `design.md` 0~6절과 `screens.md`, `acceptance.md` 5절, 오류 code는 `docs/integrate/conventions.md`의 표에 있다.
