# 1a 묶음 작업 목록 (C-8)

> 한 세션 크기의 작업이다. 작업 하나는 "이 테스트 클래스들을 통과시킨다"로 적는다. 묶음이 끝나면 이 파일은 지운다.
> 구현 탭은 한 번에 하나만 돈다(이 PC의 메모리). 차례는 아래 번호다. 탭이 합쳐지면 메인이 `pending.txt`에서 그 탭의 줄을 지운다.

## 1. 서버

| 차례 | 탭 | 맡는 것 | 통과시킬 테스트 클래스 |
|---|---|---|---|
| 1 | `s1a-i-auth` | 마이그레이션 V5 전부(`design.md` 1절의 표와 칸 넷), 계정의 세션 모두 지우기, 비밀번호 찾기와 재설정, 내 정보(이름·언어·비밀번호), 재설정 토큰 정리 작업, `Retry-After` 계산 | `auth`의 `OwnPasswordTest`, `OwnProfileTest`, `PasswordResetCleanupTest`, `PasswordResetConfirmTest`, `PasswordResetRequestTest`, `PasswordResetTokenTest`, `RetryAfterTest`. `SessionEndingTest`와 `SuspendedDuringRequestTest`, `SignInDuringPasswordChangeTest`는 2번 탭의 정지·역할 API가 있어야 다 통과하므로, 본인의 비밀번호 변경과 재설정에 걸린 메서드만 통과시킨다 |
| 2 | `s1a-i-people` | 구성원 관리(목록 필터·검색, 이름, 역할, 정지·풀기, 비밀번호 초기화), 마지막 관리자 검사, 초대 다시 보내기·취소, 고객사 고치기·상태, 소속 더하기·빼기, 배정 빼기, 이 묶음의 감사 행 | `project`의 `AccountAdminTest`, `AccountListFilterTest`, `CompanyNotActiveTest`, `CompanyUpdateTest`, `InvitationResendCancelTest`, `LastAdminTest`, `MembershipTest`, `PeopleMailTest`. `boundary.CancelledInvitationMailTest`. `ops.PeopleAuditTest`, `ops.SignInAuditTargetTest`. 1번 탭이 남긴 `auth`의 세 클래스 |
| 3 | `s1a-i-photo` | 프로필 사진(의존성 `imageio-webp`, D-53), 사람을 담는 응답의 `photoUpdatedAt`, 문의 등록의 파일 20개와 횟수 제한, 글 입력의 NUL·짝 없는 서로게이트, 화면 파일의 `Cache-Control` | `photo`의 `PhotoAccessTest`, `PhotoResponseTest`, `PhotoUploadTest`. `boundary.PhotoBoundaryTest`. `attachment.FilingFileCountTest`. `ops.TextInputTest`. deploy 태그 `PhotoLoadTest`, `ScreenFilesCacheTest`, `UpgradeFrom010Test`(이 PC에서 돌리지 않는다. CI가 돈다) |

- 마이그레이션은 1번 탭이 V5 하나로 모두 쓴다. 뒤 탭에 칸이 더 필요하면 V6을 더하고 보고에 이유를 적는다.
- 보안 설정의 관리자 전용 목록에 `PATCH /api/console/companies/{code}`와 `PUT …/status`를 넣는 것은 2번 탭이 한다.
- 계약 껍데기가 넣어 둔 고정값(`locale: null`, `companies: []`, `cancelled: false`, `photoUpdatedAt: null`)을 진짜 값으로 바꾸는 것은 그 칸을 맡은 탭이 한다(내 정보는 1번, 취소 표시는 2번, 사진은 3번).

## 2. 웹

| 차례 | 탭 | 맡는 것 | 통과시킬 테스트 파일 |
|---|---|---|---|
| 4 | `s1a-i-web-auth` | 비밀번호 찾기·재설정(두 앱), 내 정보(두 앱), `PhotoField`, 로그인의 링크, 화면 머리의 사진, CSRF 다시 보내기, `safeNext` | `C16`·`P9PasswordForgot`, `C17`·`P10PasswordReset`, `C6`·`P6MyInfo`, `PhotoField`, `C1`·`P1Login.1a`, `header.1a`, `csrfRetry.1a`, `safeNext.1a` |
| 5 | `s1a-i-web-admin` | 구성원 목록, 고객사 목록·상세, 문의 등록·상세의 바뀌는 곳, 상태 배지의 "초대 취소됨"(kind `company`는 지운다) | `C10MemberList.1a`, `C8CompanyList`, `C9CompanyDetail.1a`, `C4IssueDetail.1a`, `P4IssueCreate.1a`, `P5IssueDetail.1a`, `StatusBadge.cancelled` |

웹 탭은 서버 탭 셋이 합쳐진 뒤에 띄운다. 화면을 실제 API 위에서 눌러 보기 위해서다(브라우저 확인은 C-10).

## 3. 구현이 받쳐야 하는 가정

테스트가 기준을 어떻게 읽었는지는 테스트 코드가 가장 정확하다. 탭 보고의 "운영 코드가 받쳐 줘야 하는 가정" 절에 모아 두었다.

| 탭 | 읽을 보고(`C:\Users\minju\.claude\popdesk\tasks\`) |
|---|---|
| 1 | `s1a-t-auth-report.md` 5절 |
| 2 | `s1a-t-people-report.md`의 가정 절, `s1a-tfix-report.md` 4절(마지막 관리자 테스트가 붙잡는 곳) |
| 3 | `s1a-t-photo-report.md` 5절 |
| 4, 5 | `s1a-t-web-report.md` 5절. 그 보고의 "중지"와 kind `company`는 "멈춤"과 kind `project`로 읽는다 |

계약과 설계에서 정한 것은 `design.md` 1~3절과 5-1절, 오류 code는 `docs/integrate/conventions.md`의 표에 있다.
