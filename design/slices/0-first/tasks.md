# 첫 묶음 작업 목록

> `process.md` B "나눠 쓰기"와 C-8의 분해다. 한 줄이 탭 세션 하나다. 묶음이 끝나면 지운다(D-27).
> 수용 기준 104개(`acceptance.md` 4절)를 테스트 세션 열 개에 하나씩만 맡겼다. 구현 작업(C-8)은 C-7 점검이 끝난 뒤 테스트 파일 이름을 넣어 다시 고친다.

## 1. 순서는 계약, 테스트 바탕, 테스트 세 물결, 실패 확인, 점검, 구현이다

| 차례 | 탭 | 역할(`start-tab.ps1 -Role`) | 앞에 끝나야 하는 것 |
|---|---|---|---|
| 1 | `s0-c4-server`, `s0-c4-web` | other | 없음. 둘은 같이 돈다 |
| 2 | `s0-t0-base` | tests | `s0-c4-server` 병합 |
| 3 | `s0-t-auth`, `s0-t-proj`, `s0-t-issue`, `s0-t-web-portal` | tests | `s0-t0-base` 병합 |
| 4 | `s0-t-att`, `s0-t-notify`, `s0-t-ops`, `s0-t-web-console` | tests | 3의 서버 탭 병합 |
| 5 | `s0-t-start`, `s0-t-boundary`, `s0-t-deploy`, `s0-t-e2e` | tests | 4의 서버 탭 병합 |
| 6 | 메인이 C-6 실패 확인 | 스크립트 | 5 병합 |
| 7 | `s0-c7-review`, 사람 승인 | other | C-6 잠금 목록 커밋. 2026-10-09 승인함(0215e1b) |
| 8 | 구현 탭(3절) | impl | C-7 승인 |

- 한 물결에 서버 테스트 탭은 셋까지다. Testcontainers를 쓰는 Maven 실행이 셋을 넘으면 16GB에서 버티지 못한다. 웹 테스트 탭(Vitest)은 가벼워서 넷째로 붙인다.
- 2를 따로 둔 까닭: 모든 서버 테스트가 같은 도움 코드(호스트별 HTTP 클라이언트, 세션·CSRF, API로 만드는 픽스처, GreenMail, 웹훅 받는 쪽, 로그 잡기, 고정 `Clock`)를 쓴다. 이것을 여러 탭이 따로 만들면 같은 파일을 고치거나 같은 일을 다르게 짠다.
- 물결 사이를 병합으로 끊는 까닭: 뒤 물결이 앞 물결의 픽스처 쓰는 법을 보고 맞춘다. 같은 물결의 탭끼리는 파일이 겹치지 않는다(2절).

## 2. 테스트 세션은 수용 기준 절 단위로 나누고, 파일은 패키지로 가른다

서버 테스트는 `server/src/test/java/io/popdesk/` 아래 탭마다 패키지 하나를 쓴다. 도움 코드 패키지 `support`는 `s0-t0-base`만 고친다. 뒤 탭이 도움 코드를 더 바라면 자기 패키지에 두고 보고에 적는다. 메인이 다음 물결 전에 `support`로 옮길지 정한다.

| 탭 | 패키지·경로 | 기준 | 수 |
|---|---|---|---|
| `s0-t0-base` | `support` | 없음. 도움 코드와 그것이 도는지 보는 테스트 하나 | 0 |
| `s0-t-start` | `start` | 4.1 S0-AC-01~12, 4.12 S0-AC-103 | 13 |
| `s0-t-auth` | `auth` | 4.2 S0-AC-13~20, 4.3 S0-AC-21~28 | 16 |
| `s0-t-proj` | `project` | 4.4 S0-AC-29~32, 4.5 S0-AC-33~40·101 | 13 |
| `s0-t-issue` | `issue` | 4.6 S0-AC-41~50, 4.7 S0-AC-51~55 | 15 |
| `s0-t-att` | `attachment` | 4.8 S0-AC-56~63·65·99·102 | 11 |
| `s0-t-notify` | `notify` | 4.9 S0-AC-66~77 | 12 |
| `s0-t-ops` | `ops` | 4.10 S0-AC-78·80~86·100 | 9 |
| `s0-t-boundary` | `boundary` | 4.12 S0-AC-92·93·98 | 3 |
| `s0-t-deploy` | `deploy`(업그레이드 틀과 견본 확인 포함) | S0-AC-64·79·88~91·95~97·104 | 10 |
| `s0-t-web-portal` | `web/portal/src/**/*.test.tsx` | S0-AC-87, 화면 P-1~P-5와 2절 공통 상태 | 1 |
| `s0-t-web-console` | `web/console/src/**/*.test.tsx`, `web/packages/ui/**/*.test.tsx` | 화면 C-1~C-4, C-8~C-10, C-18, 5절 공용 컴포넌트 | 0 |
| `s0-t-e2e` | `C:\Users\minju\popdesk-holdout\` | 4.12 S0-AC-94 | 1 |

합은 104다. 나눈 기준:

- S0-AC-64(동시 10MB 부하)는 첨부지만 실제 앱 이미지를 `-Xmx384m`로 띄워야 해서 이미지·compose를 다루는 `s0-t-deploy`에 둔다. S0-AC-79(Grafana), S0-AC-91(계약 파일 비교)도 같은 까닭이다. S0-AC-91은 바닥의 `OpenApiFilesTest`와 CI가 이미 하는 일이라 C-6에서 `red-allow.txt`에 들어갈 후보다.
- S0-AC-87은 화면의 413 안내라 포털 테스트에 둔다. 운영 문서의 프록시 설정 문단은 테스트가 아니라 C-7 점검에서 사람이 본다.
- S0-AC-92·98(고객사 경계, 상담 쪽 정보)은 문의·댓글·첨부·메일을 모두 거친다. 그래서 그 셋을 쓴 뒤인 5번째 차례에 둔다. 경계 테스트 목록(S0-AC-93)은 `s0-t-boundary`가 모든 엔드포인트를 계약 파일에서 읽어 만든다.
- 웹 쪽 기준 ID가 없는 화면 테스트는 이름에 화면 ID(`P-4`, `C-10`)를 단다. `trace.py`는 기준 ID만 세므로 웹 화면 테스트의 빠짐은 C-7에서 `screens.md`와 대 본다.

모든 테스트 탭이 지킬 것(지시서에 넣는다):

- HTTP와 생성된 클라이언트로만 시험한다. 서버 내부 클래스는 참조하지 않는다. DB를 봐야 하는 것(스키마, 잠금, 행 상태)만 예외로 JDBC로 본다.
- 모호한 기준은 추측하지 않고 자기 보고 파일의 "질문" 절에 적는다. `questions.md`는 메인이 보고를 모아 쓴다(여러 탭이 한 파일을 고치지 않게).
- 자기 테스트만 돌린다. `check-test-style.py --all-strict`를 자기 경로에 돌려 통과시킨다.
- 테스트는 계약의 501 위에서 돈다. 픽스처 단계의 실패도 단언 실패(`AssertionError`)로 나게 짠다. 그래야 C-6의 `check-red.py`가 "단언 실패"로 읽는다.

## 3. 구현은 일곱 차례로 돌고, 테스트 클래스는 쓰는 픽스처까지 선 탭에 맡긴다

`process.md` C-8. 같은 파일을 두 탭이 고치지 않게 마이그레이션은 한 탭이 모두 쓴다.

| 차례 | 탭 | 맡는 것 | 기준 | 클래스 수 |
|---|---|---|---|---|
| 1 | `s0-i-schema` | 마이그레이션 전부(이 묶음의 모든 테이블), 앱 잠금, 기동 순서, 팀 1, 첫 관리자, `*_FILE`, 시작 로그, `Clock`, `tenant_id` 예외 목록(`server/README`) | S0-AC-01~12·103 | 7 |
| 2 | `s0-i-account` | 호스트 나누기, 관리 포트, 보안 머리글, 쿠키·CSRF, 믿는 프록시, 링크 만들기, 오류 본문·확인 순서·재시도, JSON 로그와 가리기, 로그인·세션·잠금·수 제한, 고객사·배정, 초대·수락·링크 복사, 감사 로그와 정해진 JSON | S0-AC-13~40·83·85·86·100·101 | 32 |
| 3 | `s0-i-issue` | 문의·댓글, 범위를 받는 저장소 꼴 | S0-AC-41~55·84 | 10 |
| 4 | `s0-i-notify` | `notification` 모듈: 보내기 대기, 메일, 웹훅, 운영 경보, 시스템 상태, 정리 작업 기록, 지표 | S0-AC-66~78·80~82 | 20 |
| 5 | `s0-i-att` | 첨부 저장·내려받기·지우기·정리, 동시 수 상한 | S0-AC-56~63·65·99·102 | 21 |
| 6 | `s0-i-deploy` | Dockerfile, 릴리스·개발·관측 compose, Grafana 대시보드, `popdesk` 명령, 업그레이드 틀, 견본 데이터 생성기, 운영 문서의 프록시 문단 | S0-AC-64·79·87(문서)·88~91·95~97·104 | 11 |
| 6 | `s0-i-console` | 콘솔 화면, 공용 컴포넌트(`web/packages/ui`) | `web/console`, `web/packages/ui`의 웹 테스트 전부 | 웹 |
| 7 | `s0-i-portal` | 포털 화면 | `web/portal`의 웹 테스트 전부, S0-AC-87 | 웹 |
| 끝 | 메인 | 경계·상담 쪽 정보·빈 설치 E2E를 합친 `main`에서 돌림 | S0-AC-92~94·98 | 4 |

- 클래스를 맡기는 규칙: 그 클래스가 보는 기준의 탭과 그 클래스가 쓰는 픽스처(`Fixtures`의 로그인·고객사·초대는 account, 문의·댓글은 issue, 첨부는 att, 메일 서버·웹훅 받는 쪽은 notify)의 탭 가운데 가장 늦은 탭이 맡는다. 클래스가 통째로 초록이 되는 첫 탭이 그 탭이다.
- "기준 일부는 X"라고 적힌 클래스는 X 탭이 코드를 쓰지만 X 탭에서는 픽스처가 없어 돌지 않는다. 맡은 탭이 그 클래스를 초록으로 만들다 앞 탭 코드의 잘못을 찾으면 고쳐도 되고, 고친 것을 보고에 적는다. 탭은 차례대로 돌아서 같은 파일을 동시에 고치지 않는다.
- 처음 계획은 `s0-i-web`과 `s0-i-account`를 나누고 `s0-i-issue`·`s0-i-notify`를 같이 돌렸다. 테스트 클래스를 대 보니 web 쪽 클래스는 `ManagementPortTest` 하나 말고는 모두 로그인이나 고객사 픽스처를 써서 account 없이는 돌지 않았다. notify 쪽 클래스도 댓글 픽스처를 쓴다. 그래서 web과 account를 한 탭으로 합치고 issue 뒤에 notify를 둔다. 첫 관리자 비밀번호 규칙(`FirstAdminPassword*Test`)과 `FirstStartTest`·`SecretFileTest`·`TimeTest`도 로그인을 써서 account가 맡는다.
- 각 탭은 맡은 클래스와 함께 이미 초록인 테스트(바닥 테스트, `red-allow.txt`의 통과, 앞 탭이 맡은 클래스)를 깨지 않는다.
- 마이그레이션을 한 탭에 몬 까닭: 버전 번호가 겹치지 않게 하고, 잠금 순서(`architecture.md` 4절)를 지키는 외래 키·제약을 한 사람이 같이 본다. 뒤 탭이 칸을 더 바라면 새 버전으로 더하고 이미 병합한 파일은 고치지 않는다(D-24).
- 댓글이 메일 대기 행을 넣는 자리는 `notification`의 공개 API이고, 그 인터페이스는 `s0-i-account`(초대 메일 대기 행)가 먼저 만든다.
- 화면 탭은 실제 API가 선 뒤에 돈다(C-9). Vitest라 가벼워 `s0-i-console`은 `s0-i-deploy`와 같이 돈다. 공용 컴포넌트는 `s0-i-console`만 고치고 포털 탭은 쓰기만 해서 포털이 뒤다. `pending.txt`의 웹 줄은 두 앱이 같은 글롭을 써서 두 화면 탭이 모두 병합된 뒤 지운다.

### 탭마다 통과시킬 서버 테스트 클래스

`io.popdesk.` 아래 패키지 이름부터 적는다. `trace.py design/slices/0-first`와 `tests.lock`(C-6 잠금, 0215e1b)에서 뽑았다. 추상 클래스와 `support` 패키지는 빠진다.

- `s0-i-schema`(7): `start.DatabasePrivilegeTest`, `start.FirstAdminMissingTest`, `start.InstanceLockTest`, `start.MigrationHistoryTest`, `start.MigrationPortsTest`, `start.StartLogTest`, `start.TenantIdGateTest`
- `s0-i-account`(32): `auth.FirstAdminPasswordNineCharactersTest`, `auth.FirstAdminPasswordOnlyWhitespaceTest`, `auth.FirstAdminPasswordSameAsEmailTest`, `auth.FirstAdminPasswordSeventyThreeCharactersTest`, `auth.ForwardedHeadersTest`, `auth.HostRoutingTest`, `auth.HttpsPublicAddressTest`, `auth.InvitationLinkTest`, `auth.LockoutTest`, `auth.ManagementPortTest`, `auth.PasswordRulesTest`, `auth.SecurityHeadersTest`, `auth.SessionCookieTest`, `auth.SignInRateLimitTest`, `auth.SignInTest`, `auth.UntrustedProxyTest`, `ops.AuditLogTest`, `ops.CanonicalAuditJsonTest`, `project.CompanyCreateTest`, `project.CompanyListTest`, `project.CompanyPermissionTest`, `project.InvitationAcceptTest`, `project.InvitationInvalidTokenTest`, `project.InvitationLinkCopyTest`, `project.InvitationLocaleTest`, `project.InvitationRejectTest`, `project.InvitationTest`, `project.InvitationTokenTest`, `project.PendingInvitationListTest`, `start.FirstStartTest`(기준 일부는 s0-i-schema), `start.SecretFileTest`(기준 일부는 s0-i-schema), `start.TimeTest`(기준 일부는 s0-i-schema)
- `s0-i-issue`(10): `auth.SessionLifetimeTest`(기준 일부는 s0-i-account), `issue.CommentTest`, `issue.IssueCreateTest`, `issue.IssueListTest`, `issue.IssueRateLimitTest`, `issue.PortalIssueFieldsTest`, `ops.DatabaseConflictTest`(기준 일부는 s0-i-account), `ops.LogPrivacyTest`(기준 일부는 s0-i-account), `ops.ScopedReadRulesTest`, `project.AgentAssignmentTest`(기준 일부는 s0-i-account)
- `s0-i-notify`(20): `issue.InternalCommentMailTest`(기준 일부는 s0-i-issue), `notify.CommentMailTest`, `notify.InvitationMailTest`, `notify.KoreanMailTest`, `notify.MailQueueRowTest`, `notify.NoSmtpMailLogTest`, `notify.NoWebhookTest`, `notify.QueueTransactionTest`, `notify.RetryTest`, `notify.SendLoopTest`, `notify.SmtpPlainAllowedTest`, `notify.SmtpStartTlsTest`, `notify.StrictTransportTest`, `notify.WebhookMessageTest`, `notify.WebhookSecretTest`, `ops.LogSecretsWithSmtpTest`(기준 일부는 s0-i-account), `ops.OutboxMetricsTest`, `ops.SystemStatusTest`, `ops.SystemStatusWithSmtpTest`, `project.InvitationWithSmtpTest`(기준 일부는 s0-i-account)
- `s0-i-att`(21): `attachment.AttachmentArchitectureTest`, `attachment.AttachmentCleanupTest`(기준 일부는 s0-i-notify), `attachment.AttachmentConcurrencyLimitTest`, `attachment.AttachmentDeleteTest`, `attachment.AttachmentDownloadTest`, `attachment.AttachmentLimitsTest`, `attachment.AttachmentNotFoundTest`, `attachment.AttachmentQuotaTest`, `attachment.AttachmentRateLimitTest`, `attachment.AttachmentStatementLogTest`, `attachment.AttachmentStorageTest`, `issue.IssueConcurrencyTest`(기준 일부는 s0-i-issue), `issue.IssueNumberTest`(기준 일부는 s0-i-issue), `issue.IssueVersionTest`(기준 일부는 s0-i-issue), `ops.CheckOrderTest`(기준 일부는 s0-i-account), `ops.CleanupRecordTest`(기준 일부는 s0-i-notify), `ops.ErrorBodyTest`(기준 일부는 s0-i-account), `ops.MetricsTest`(기준 일부는 s0-i-notify), `ops.OpsAlertTest`(기준 일부는 s0-i-notify), `project.NoStoreTest`(기준 일부는 s0-i-account), `start.TenantIdTest`(기준 일부는 s0-i-schema)
- `s0-i-deploy`(11): `deploy.AttachmentLoadTest`, `deploy.BackupRestoreTest`, `deploy.ContractClientsTest`, `deploy.DevComposeTest`, `deploy.ImplicitTlsMailTest`(기준 일부는 s0-i-notify), `deploy.ObservabilityComposeTest`, `deploy.ReleaseComposeTest`, `deploy.ResetPasswordTest`, `deploy.UpgradeHarnessTest`, `issue.PortalIssueContractTest`(기준 일부는 s0-i-issue), `ops.EndpointMappingsTest`
- 메인(끝)(4): `boundary.BoundaryListTest`(기준 일부는 s0-i-deploy), `boundary.CompanyBoundaryTest`(기준 일부는 s0-i-att), `boundary.QueuedMailBoundaryTest`, `boundary.StaffIdentityTest`

`s0-i-account`가 돌려 보니 처음 배정은 같은 패키지 도움 클래스(`OpsWorld` 등)와 SMTP를 켠 바탕(`ConnectedAppTest`, 초대 토큰을 메일에서 읽음)을 거쳐 쓰는 픽스처를 놓쳤다. 그래서 일곱 클래스를 옮겼다: `project.InvitationWithSmtpTest`·`issue.InternalCommentMailTest`는 notify, `ops.DatabaseConflictTest`는 issue, `ops.CheckOrderTest`·`ops.ErrorBodyTest`·`ops.CleanupRecordTest`(세 정리 작업을 모두 봄)는 att, `deploy.ImplicitTlsMailTest`(`@Tag("deploy")`, 앱 이미지로 돎)는 deploy.

## 4. C-4 계약 탭은 둘이고, 서버 탭의 엔드포인트 목록은 사용자에게 보인 뒤 병합한다

| 탭 | 하는 일 | 고치는 곳 |
|---|---|---|
| `s0-c4-server` | 이 묶음의 모든 엔드포인트를 컨트롤러 껍데기(경로, 요청·응답 타입, 본문은 501)로 더하고, 오류 `code` 이름(`screens.md` 1절의 "가칭")을 정해 `conventions.md`에 적고, `./mvnw -Popenapi test`로 계약 파일 둘을 만들고 클라이언트를 다시 만든다. OpenAPI `info.title`도 정한다 | `server/src/main/**`(테스트 아님), `docs/integrate/`, `web/packages/api/src/generated/**` |
| `s0-c4-web` | `screens.md` 5절 공용 컴포넌트의 props·story 껍데기와 두 앱의 화면 경로 껍데기 | `web/packages/ui/src/**`, `web/portal/src/**`, `web/console/src/**`(테스트 파일 아님) |

- 계약은 공개 계약이라 전체 경로다(AGENTS.md). 메인은 `s0-c4-server`의 엔드포인트 표를 사용자에게 보이고 확인을 받은 뒤 병합한다.
- 껍데기의 컨트롤러는 Spring Security 없이 둔다. 보안 설정은 S0-AC-13~20의 구현이라 `s0-i-account`가 한다(바닥과 같은 까닭, `process.md` B).
- `s0-c4-web`은 생성된 클라이언트를 쓰지 않는 props로 짠다. 두 C-4 탭이 같은 파일을 고치지 않게 하려는 것이다. 화면 탭(3절 7)이 둘을 잇는다.

## 5. 테스트 도움 코드가 정한 것은 구현이 받쳐야 한다

`s0-t0-base`가 만든 `server/src/test/java/io/popdesk/support/`(쓰는 법은 같은 폴더 `README.md`)가 아래를 가정한다. 구현 탭 지시서에 넣는다.

- 설정은 Spring `Environment`에서 키 이름 그대로 읽는다(`${POPDESK_PORTAL_HOST}`나 `environment.getProperty`). `System.getenv()`를 바로 읽거나 `@ConfigurationProperties`의 느슨한 묶기에만 기대면 테스트 값이 닿지 않는다. 모르는 키 경고(S0-AC-06)와 `*_FILE`(S0-AC-04)도 `Environment`의 속성 출처를 훑는다.
- 시각은 모두 주입한 `Clock` 빈으로 읽는다. 앱의 `Clock` 빈은 `@Primary`가 아니고 이름이 `testClock`이 아니다. DB 기본값 `now()`로 업무 시각을 정하지 않는다.
- Host는 포트를 떼고 호스트 이름만 비교한다.
- CSRF는 쿠키 `XSRF-TOKEN`, 머리글 `X-XSRF-TOKEN`이고 쿠키 값을 그대로 받는다(conventions "Sessions and CSRF").
- 메일 TLS는 JVM 기본 신뢰 저장소를 쓴다. 메일 연결에 따로 신뢰 저장소나 `mail.smtp.ssl.trust`를 두지 않는다.
- 로그는 logback `ConsoleAppender`로 표준 출력에 쓴다.
- 포트 465의 암묵적 TLS(S0-AC-74 앞부분)는 앱을 JVM 안에서 띄우는 테스트로는 시험하지 못한다(빈 포트에 뜨는 테스트 서버). `s0-t-deploy`가 앱 이미지와 같은 Docker 네트워크의 메일 서버 컨테이너(465)로 시험한다.
- C-6의 `red-allow.txt`에 `SupportSelfTest` 16건을 "도움 코드 자기 시험, 업무 동작을 보지 않는다"로 올린다.

## 6. 둘째 물결 테스트 질문으로 정한 것

구현 탭 지시서에 넣는다. 수용 기준 문장을 바꾼 것은 S0-AC-100(RFC 8785 키 순서, 소수 여섯 자리)뿐이고 나머지는 기준을 읽는 법이다.

- 크기 단위: MB·GB는 2^20·2^30바이트다(Spring `DataSize`와 같다). 10MB는 10,485,760바이트, multipart 상한은 그보다 조금 크게 둔다.
- 정기 작업(정리 작업 셋, 보내기 대기의 `failed` 지우기, 운영 경보): 실제 시간으로 짧은 주기(몇 초)마다 깨어나고, 돌 때가 됐는지는 주입한 `Clock`으로 마지막 실행 시각과 견줘 정한다. 테스트는 `clock.advance` 뒤 `Wait`로 기다린다.
- 정리 작업 기록(S0-AC-82)은 작업마다 한 행을 고쳐 쓴다. 작업 이름은 `outbox-failed-cleanup`, `attachment-cleanup`, `session-cleanup`이다.
- `failed` 행은 `failed`가 된 시각에서 7일 뒤 지운다(S0-AC-68).
- 운영 경보(S0-AC-80)의 "풀림"은 `failed` 행이 0건이 됨, 첨부 합이 80% 아래로 내려감이다. 풀리지 않으면 마지막 항목에서 24시간(주입한 `Clock`)마다 한 건 더 남긴다.
- 실패 주입: 운영 코드에 테스트 전용 엔드포인트나 설정 키를 두지 않는다. 테스트는 `DbInspector.schemaFixture`로 이름이 `test_`로 시작하는 트리거를 걸어 DB 쓰기를 실패시킨다(순번 직전 실패, ③ 실패로 남는 `pending`, 저장소 지우기 실패로 남는 `purging`, 대기 행 뒤 업무 실패, 일부러 낸 500). 그 테스트는 트리거를 끝에서 지우거나 자기 컨텍스트를 쓴다.
- 업무 행을 SQL로 바꾸는 것은 둘만 허용한다: 고객의 고객사 소속 빼기(PROJ-4 빼기는 1번 묶음), 계정 상태를 `ACTIVE` 밖으로 바꾸기(정지는 뒤 묶음). 소속에서 빠진 고객도 자기가 올린 문의는 본다(권한 모델 2번). 그래서 소속 빼기는 "볼 수 없게 됨"을 만들지 못하고, S0-AC-92는 소속을 빼도 메일이 나가는지를 본다. S0-AC-69의 보내지 않음은 계정 상태로 본다. 앞서 이 줄이 소속 빼기로 "볼 수 없게 됨"을 만든다고 적은 것은 권한 모델과 어긋나 고쳤다.
- S0-AC-63의 "`pending`으로 남음"은 ① 뒤에 나는 실패만이다. ① 전에 끝나는 검증 실패(400)는 첨부 행을 만들지 않는다.
- 첨부 쓰기 규칙(S0-AC-56의 `setBytes` 금지)은 첨부 저장소 패키지에만 건다. 패키지 이름은 `s0-t-ops`가 제안한 이름(`ScopedReadRulesTest`)을 따른다.
- 감사(S0-AC-83): 일의 종류는 `FIRST_ADMIN_CREATED`, `COMPANY_CREATED`, `AGENT_ASSIGNED`, `INVITATION_CREATED`, `INVITATION_LINK_CREATED`, `SIGN_IN_SUCCEEDED`, `SIGN_IN_FAILED`, `ACCOUNT_LOCKED`, `ADMIN_PASSWORD_RESET`, `BACKUP_CREATED`, `DATABASE_RESTORED`. 없는 이메일의 로그인 실패는 감사 행을 남기지 않는다. 있는 계정의 실패는 한 사람을 그 계정으로 둔다.
- 지표 이름과 `CanonicalAuditJson`의 클래스·시그니처, 범위 타입(`io.popdesk.helpdesk.Viewer`)은 `s0-t-ops` 보고의 제안을 따른다. `s0-t-deploy`의 Grafana 대시보드도 같은 지표 이름을 쓴다.
- 댓글 알림 메일 제목에는 문의 제목이 들어간다(S0-AC-71). 문의 제목의 줄바꿈은 막지 않는다.
- 웹훅 메시지 스냅샷 파일(S0-AC-75)은 C-10에서 메인이 고정한다.
- C-7 점검에서 사람이 코드로 보는 것: multipart 상한과 `file-size-threshold` 0(S0-AC-99), 40P01 재시도(S0-AC-85, 55P03만 테스트가 있다), 보낼 때 주소·제목을 읽음(S0-AC-66), 보내기 직전에 받는 사람이 문의를 볼 수 있는지 다시 봄(S0-AC-69).


## 7. 셋째 물결 테스트 질문으로 정한 것

구현 탭 지시서에 넣는다.

- 앱 잠금을 기다리는 시간(S0-AC-11)은 `POPDESK_INSTANCE_LOCK_WAIT`(ISO-8601, 기본 `PT30S`)다. `configuration.md`에 올렸다.
- 실패한 마이그레이션의 복구 절차(S0-AC-08)는 `docs/operate/upgrade.md`에 쓰고(구현 몫), 로그 한 줄에 실패한 버전과 그 경로를 함께 적는다.
- 첫 관리자 만들기가 다른 이유로 실패하는 경우(S0-AC-02)도 시험한다. 테스트는 계정 테이블을 모양으로 찾아 `test_` 트리거로 실패시킨다(6절의 실패 주입).
- readiness가 내려간 상태(S0-AC-02)는 관리 포트 `/actuator/health/readiness`가 503이고 status가 `UP`이 아닌 것으로 본다(`DOWN`이든 `OUT_OF_SERVICE`든). liveness는 200이다.
- 시작을 막는 로그 문구(S0-AC-07·10)에는 기준이 적은 것만 넣는다: S0-AC-07은 DB 버전과 "backup", S0-AC-10은 빠진 권한(`CREATE`)과 DB 사용자 이름.
- `tenant_id` 예외 목록(S0-AC-103)은 `server/README.md`의 `| Table | Reason |` 표다.
- 계정 상태 값은 `INVITED`·`ACTIVE`·`SUSPENDED`이고 상태 칸은 이 묶음에서 `SUSPENDED`를 받는다(정지 기능은 뒤 묶음, 테스트가 SQL로 넣는다). 정지된 계정의 로그인은 401이다.
- 릴리스 compose(S0-AC-88·94): 설정 키는 Compose 환경(`${KEY}` 치환이나 `env_file: .env`)으로 받고, `.env` 말고 compose 파일 기준의 상대 경로(바인드 마운트 등)를 두지 않는다. `app` 상태 확인은 `start_period + interval × retries`가 5분보다 짧다(첫 관리자 값이 없으면 5분 안에 `unhealthy`). 관리 포트 8081은 컨테이너의 모든 인터페이스에서 받되 compose가 호스트로 열지 않는다.
- `main`의 `deploy/compose.yml`은 다음에 낼 버전(`0.1.0`)의 정확한 이미지 태그를 담고, 서비스는 `app`·`db` 둘뿐이다(S0-AC-88). 릴리스 절차가 태그와 같은지 본다.
- `popdesk restore`는 앱이 떠 있지 않을 때 새 컨테이너로 돌린다(`docker compose run --rm app popdesk restore <file>`). 백업 덤프는 `docker compose cp`로 꺼내고, 복원할 덤프는 `run`에 한 파일 마운트(`-v`)로 넣는다(`run` 컨테이너에는 `cp`를 쓸 수 없다). 구현이 `configuration.md` Commands 절에 둘 다 적는다(S0-AC-89).
- GC는 이미지가 JVM 옵션으로 박는다. 호스트에 따라 바뀌지 않게 한다(S0-AC-64).
- 보내기 대기 지표(`popdesk_outbox_queued`·`_failed`·`_sent_total`)는 채널이 꺼져 있어도 두 채널 모두 0으로 낸다(S0-AC-78·79).
- `deploy` 패키지 테스트(`@Tag("deploy")`)는 이미지를 빌드하는 CI 잡에서만 돈다. 서버 잡은 `deploy` 태그를 뺀다. 구현 뒤 메인이 `pom.xml`과 CI에 넣는다. 숨긴 E2E와 볼륨 이름이 겹치므로 둘을 함께 돌리지 않는다.
- 첫 묶음의 견본 덤프를 릴리스 산출물로 보관하는 것(S0-AC-95)은 테스트가 없고 C-7에서 사람이 본다.

C-7 점검(`s0-c7-review`)으로 정한 것:

- 두 앱의 `/`는 문의 목록 `/issues`로 보낸다(포털 P-3, 콘솔 C-3). 로그인하지 않았으면 그 화면이 401을 받아 로그인으로 간다(screens 2절). 바닥의 `App.test.tsx` 둘은 이 동작을 보는 테스트로 바꾼다.
- 빈 설치 E2E(S0-AC-94)는 첫 관리자 값과 DB 비밀번호를 넣는다. 비밀값에는 기본값이 없다.
- C-4 껍데기가 이미 하는 일도 처음부터 통과를 허용하는 이유다(process.md C-6). 그 96건은 C-10에서 변이 테스트를 꼭 돌린다.
- 404 본문은 대상이 없을 때와 볼 수 없을 때 바이트까지 같다. 문의·댓글·첨부·고객사 모두, 오류 `message`에 키나 ID를 넣지 않는다(COM-1, S0-AC-92·102).
- 권한 확인은 쓰기 전에 끝난다. 권한 없는 첨부 올리기(403·404)는 첨부 행을 만들지 않고 내용을 저장소에 쓰지 않는다.
- 첨부 동시 수(S0-AC-99)는 multipart 해석 전, 서블릿 필터에서 센다.
- 범위 없는 읽기 규칙(S0-AC-84)은 고객 데이터 저장소(`issue`·`comment`·`attachment`·`company` 패키지)에만 건다. 보내기 대기, 운영 경보, 정리 기록 저장소는 `findById`를 써도 된다. `account` 패키지는 계정 저장소만 범위 없이 읽는다.
- 보내기 직전 다시 보기(S0-AC-69)는 `notification`이 인터페이스를 두고 `helpdesk.system`이 구현한다(`notification`이 `helpdesk.system`에 기대지 않게).
- 같은 트랜잭션 기준(S0-AC-56 ②, S0-AC-66, S0-AC-83)은 6절의 `test_` 트리거로 업무 쪽 쓰기를 실패시켜 양쪽을 본다.
- 첨부 합 80% 경고 로그(S0-AC-58)에는 설정 키 이름 `POPDESK_ATTACHMENT_QUOTA`를 넣는다.
- 정리 작업은 실행 기록(S0-AC-82)을 지우기를 끝낸 뒤에 쓴다.
- 정리 작업이 `pending`을 먼저 끝낸 뒤 늦게 끝난 올리기의 상태 코드는 정하지 않는다(테스트는 400 이상과 `stored`가 아님만 본다). 그때 ②가 쓴 내용 행이 남는지는 C-10에서 코드로 본다.
- `helpdesk` 모듈의 DB 접근(`EntityManager`, `JdbcTemplate`)은 저장소 클래스에서만 한다(`ScopedReadRulesTest.rawDatabaseAccessOnlyInRepositories`).
- 표 이름에는 `issue`, `outbox`, `audit`이 들어간다. 업그레이드 틀 테스트(V999, 견본 경계값)가 이 꼴로 표를 찾는다.
- 409 본문(중복 초대 등)에 입력한 이메일을 되돌려 넣지 않는다.

구현 지시서를 쓰며 정한 것(C-8):

- 앱 스키마는 앱 DB의 `public`이다. `DatabasePrivilegeTest.userWithoutSchemaCreate`는 `public`의 `CREATE`만 빼고 앱이 멈추기를 본다. 앱이 `popdesk` 스키마를 만들면 DB `CREATE`로 지나가 뜬다. `architecture.md` 4절의 `popdesk`를 고쳤다.
- 테스트가 기준보다 엄격한 곳은 승인한 테스트를 따른다: S0-AC-87의 413 안내 문구(`screens.md` 2절 문구를 앱 본문 413에도), S0-AC-73의 가린 주소 로그는 요청 하나에 정확히 한 줄, S0-AC-77은 문의·댓글 행 말고 어떤 대기 행도 남기지 않음.
- 콘솔이 SMTP가 켜졌는지 아는 길은 `GET /api/console/system/status`의 `mail.enabled`다. `screens.md` 7절의 "관리자용 설정 요약 API"는 따로 두지 않는다.
- 첫 관리자의 이름은 `Admin`이다. 설정 키를 두지 않는다. 관리자가 로그인한 뒤 바꾸는 기능은 뒤 묶음이다(`s0-i-schema` 질문 1).
- `delivery_status`, `ops_alert`, `notification` 테이블과 `outbox.kind`·`notification.kind` 값 목록은 `s0-i-notify`가 정한다. 쓰지 않는 테이블은 그 탭이 새 버전으로 지운다. 견본 생성기가 업무 테이블마다 행을 넣어야 해서 빈 테이블을 남기지 않는다(`s0-i-schema` 질문 3·4).
- 구현 탭 보고는 훅이 worktree 밖 쓰기를 막아 탭의 scratchpad에 쓰고, 메인이 `tasks\`로 옮긴다.
- 초대 메일의 토큰: DB에는 해시만 있어 보내는 루프가 처음 토큰을 되살릴 수 없다. 보낼 때 새 토큰을 만들어 `invitation.token_hash`를 바꾸고 그 링크를 메일에 넣는다. 링크는 `PublicAddress.invitationLink`로 만든다(`s0-i-account` 질문 2).
- 정리 작업 셋: `outbox-failed-cleanup`과 `session-cleanup`, 기록 API는 `s0-i-notify`, `attachment-cleanup`은 `s0-i-att`가 만든다.
- `issue.IssueNumberTest`는 첫 문의를 파일과 함께 올려 att로 옮겼다. `s0-i-issue`가 `ScopedReadRulesTest` 때문에 먼저 만든 첨부 뼈대(`AttachmentRepository`, `AttachmentService`, `storage`)는 `s0-i-att`가 바꿔도 된다.
- 초대 행은 수락 뒤에도 남는다. 토큰으로 찾는 쿼리는 모두 `status = INVITED`를 건다. 지우면 `NoWebhookTest`·`NoSmtpMailLogTest`의 "지워지는 행 없음" 통계 검사가 PostgreSQL 통계 지연(최대 10초)으로 흔들렸다. V1의 "deleted on accept" 주석은 SQL 주석이라 DB에 남지 않아 고치지 않고, `server/README.md` 표가 맞는 설명이다(`s0-i-notify` 질문 2·3).
- 상담 쪽 답변 메일에는 댓글 내용을 넣지 않는다(제목·문의 키·포털 링크만). 웹훅 글은 설치 언어, 메일은 받는 계정 언어다.
- `notify.MailQueueRowTest.invitationMailForAcceptedAccountIsDropped`의 마지막 단언은 테스트 결함이었다. 앞서 부른 `mailQueueTable`이 붙잡은 probe 메일까지 세었다. 메인이 그 주소로 거르게 고치고 다시 잠갔다(C-9 "테스트가 틀렸다고 보는 것").
- `s0-i-att`의 V4가 첨부 CHECK를 `stored`에만 걸었다. 새 문의와 함께 온 파일의 등록이 실패하면 문의 ID 없는 행을 정리 작업이 `purging` → `purged`로 끝내야 해서다.
- `POPDESK_STORAGE`는 이번 판에 `db`만 받고 `disk`·`s3`면 뜨지 않는다(acceptance 53줄, 5번 묶음에서 연다).
- 화면 정적 파일: 앱이 호스트별로 포털·콘솔 빌드를 내준다(GET/HEAD, `/api/`·관리 포트 밖, 없는 경로는 index.html). 맡은 탭이 없어서 `s0-i-deploy`가 Dockerfile 웹 빌드와 함께 넣는다(그 탭 질문).
- 공용 컴포넌트에서 고칠 것 셋(`s0-i-portal` 보고 5절): `IssueList`가 받은 올린 사람을 그리지 않음, `Select readOnly`가 Tab 자리를 가져감, `MarkdownEditor` `minHeight`가 숫자만 받음. 첫 묶음 테스트는 모두 통과해서 지금 고치지 않고 1번 묶음의 공용 컴포넌트 작업에 넣는다. 두 앱의 `app/` 공통 코드 사본도 그때 공용 패키지로 옮길지 정한다.
