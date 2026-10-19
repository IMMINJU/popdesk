# 11 묶음 설계

> `acceptance.md`의 기준을 어떻게 만드는지 적는다. 조사는 메인 세션과 서브에이전트가 했다(2026-10-19). [소스]는 지금 코드에서 읽은 것, [추론]은 읽지 않고 짐작한 것이다.
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-19).

## 0. 메인이 정한 것

| # | 정한 것 |
|---|---|
| 1 | 의존성: `spring-security-oauth2-client`와 `spring-security-oauth2-jose`(버전은 Boot BOM). 스타터(`spring-boot-starter-oauth2-client`)는 쓰지 않는다. 자동 구성이 `spring.security.oauth2.client.*` 키를 찾고, 그 키를 쓰지 않으므로 둘 다 필요 없다 [추론: 스타터 없이도 Boot의 OAuth2 자동 구성 클래스가 클래스패스로 켜지는지는 C-4 계약 탭이 본다]. 쓰는 부품: `NimbusJwtDecoder`(JWKS), `RestClientAuthorizationCodeTokenResponseClient`, `OidcIdTokenValidator`. 쓰지 않는 것: `oauth2Login()`, `ClientRegistrationRepository` 빈, `OAuth2AuthorizedClientService` |
| 2 | 코드는 `helpdesk.account`에 둔다. `Sessions`, `AccountRepository`, `InvitationService`가 이 패키지 안에서만 보인다 [소스: `Sessions`와 `SignInService`가 패키지 공개]. 새 클래스: `OidcSettings`(설정 읽기와 시작 검사, `InitializingBean`에서 던짐), `OidcProvider`(발견 문서와 JWKS를 1시간 기억, 실패는 기억하지 않음), `OidcSignInService`(시작과 돌아오기, 계정 맞추기), `OidcLinkRepository`, `OidcRequestRepository`, `OidcStatus`(메모리 수), 순수 계산 `SafeNext`, `IdTokenClaims`(클레임 판정). 웹 쪽은 `SignInFlow`에 `oidcStart`, `oidcCallback`을 더하고 `helpdesk/web/publicapi/PublicOidcController`가 부른다 |
| 3 | 시작(`GET /api/public/oidc/start`): `IpRateLimit`의 새 인스턴스(60초에 20번)와 10분 안의 요청 수 상한(10,000) → `OidcProvider.metadata()`(못 받으면 `unavailable`, 실패는 30초 기억) → 32바이트 난수 셋(`state`, `nonce`, 브라우저 값)과 PKCE 검증값 → 10분 지난 요청 지우기와 새 요청 넣기(한 트랜잭션) → `POPDESK_OIDC` 쿠키에 브라우저 값 → 302. 표에는 `state`, `nonce`, 브라우저 값의 SHA-256만 둔다. PKCE 검증값은 토큰 주소에 보내야 해서 그대로 둔다 |
| 4 | 돌아오기(`GET /api/public/oidc/callback`): `state` 해시로 요청을 `DELETE … RETURNING`(한 번만 쓰임) → 쿠키의 브라우저 값 해시, 앱, 10분 확인 → 쿠키 지우기 → `error`면 쿼리 없이 로그인 화면 → 토큰 바꾸기(연결 10초, 읽기 10초) → ID 토큰 디코딩(`NimbusJwtDecoder`, 모르는 `kid`면 JWKS를 한 번 다시 받음)과 `IdTokenClaims` 판정 → 계정 맞추기(아래 5) → `Sessions.start` → 302. 세션의 로그인 시각은 비밀번호 로그인처럼 확인 전에 잰다 [소스: `SignInFlow.signIn`이 `began`을 확인 전에 잼] |
| 5 | 계정 맞추기는 한 트랜잭션: `oidc_link`를 (`issuer`, `subject`)로 찾음 → 있으면 그 계정을 `lockById` → 없으면 `email_verified`와 `email`(ASCII만) 확인, `findByEmail` → 계정 `lockById` → 잠근 행의 이메일이 아직 토큰의 이메일과 같은지(ASCII 대소문자 무시) → 그 계정의 같은 `issuer` 묶음이 있으면 실패 → 잠근 행으로 상태·역할·초대 기한 확인 → 묶음 넣기(`ON CONFLICT DO NOTHING`, 넣지 못하면 다시 찾아 같은 계정이면 그대로 진행, 아니면 실패) → 수락 전이면 켜기 → 감사. 같은 때 두 번 묶기(S11-AC-20)는 계정 행 잠금과 유일 색인이 막는다. 새 잠금은 `FOR NO KEY UPDATE`로 쓴다(`issue.IssueConcurrencyTest`의 규칙, process C-7) [추론: `AccountRepository.lockById`의 잠금 꼴은 C-4 전에 본다] |
| 6 | 수락 전 계정 켜기: `InvitationService`에 `activateByOidc(accountId)`를 둔다. 초대 행을 잠그고 7일·취소를 확인한 뒤, 계정을 `ACTIVE`로, 비밀번호 칸을 `PasswordHashes.RESET`으로, 실패 수와 잠금을 비운다(`updateActivated`의 꼴 [소스: `AccountRepository:172-176`]). 초대 토큰은 계정이 `INVITED`가 아니면 쓰이지 않는다 [소스: `InvitationService.java:182-183`]. `RESET`은 쓸 수 없는 값이라 비밀번호 로그인은 실패하고 [소스: `PasswordHashes.usable`], 다시 켜기의 "수락 전" 판정은 비밀번호 칸이 `NULL`인지로 본다 [소스: 서브에이전트 조사, `AccountService` 293줄 근처. C-4 전에 다시 본다] |
| 7 | 묶음 풀기는 묶음 지우기, `Sessions.endAll`, 감사를 한 트랜잭션에서 한다. 익명화: `AccountService.anonymize`에 `oidcLinks.deleteOf(accountId)`를 더한다(초대·재설정 링크 지우기 옆 [소스: `AccountService.java:253-282`]) |
| 8 | 시스템 상태: `SystemStatus`에 `oidc` 칸을 더한다. `SystemStatus`와 컨트롤러는 `notification` 쪽에 있고 지금 `helpdesk`를 하나도 부르지 않는다. `helpdesk.account`는 이미 `notification`을 부른다 [소스: `InvitationService.java:23-25` 등]. 그래서 컨트롤러가 `helpdesk.account`를 부르면 새 순환이 생겨 `PackageCyclesTest`와 `ModulesTest`가 실패한다. `notification` 쪽에 인터페이스(`OidcSignInState` 꼴)를 두고 `helpdesk.account`의 `OidcStatus`가 구현한다(`AuditEntries`, `BrandingName`과 같은 꼴 [소스: 서브에이전트 조사]) |
| 11 | 계정 내보내기(PII-3)에는 OIDC 묶음을 넣지 않는다. 잠긴 `privacy/AccountExportTest`가 파일과 계정의 칸 목록을 정확히 본다 [소스: `ExportSteps.java:27-31`]. `sub`는 제공자가 정한 뜻 없는 값이고, 묶음이 있다는 것은 C-10에 보인다. 넘긴 일로 둔다 |
| 9 | 로그인 화면: 두 앱의 로그인 화면이 거의 같은 사본이다 [소스: 서브에이전트 조사, `C1Login.tsx`·`P1Login.tsx`]. OIDC 단추, `oidc` 쿼리 문장, `sign-in-options` 읽기는 `@popdesk/app`에 `OidcSignIn` 조각과 `useSignInOptions`로 두고 두 화면이 가져다 쓴다. 두 화면 자체를 합치는 일은 이 묶음에서 하지 않는다 |
| 10 | 시험 제공자는 `ghcr.io/navikt/mock-oauth2-server:2.1.10`(D-66), `JSON_CONFIG={"interactiveLogin":true}`. 테스트는 시작의 302를 따라 제공자의 인가 주소에 `username`(= `sub`)과 `claims`(JSON)를 POST해 코드를 받는다. 앱과 제공자가 같은 `localhost:포트`를 보아야 `iss`가 맞는다. 서버 테스트 도움은 `support/`에 하나 둔다 |

## 1. 표 (V24)

```sql
-- Owned by helpdesk (accounts). An account's sign-in with an OIDC provider (AUTH-6): once matched by
-- a verified email, later sign-ins match by issuer and subject only.
CREATE TABLE oidc_link (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL,
    account_id bigint NOT NULL REFERENCES account (id),
    issuer text NOT NULL,
    subject text NOT NULL,
    linked_at timestamptz NOT NULL,
    CONSTRAINT oidc_link_issuer_subject_key UNIQUE (issuer, subject),
    CONSTRAINT oidc_link_account_issuer_key UNIQUE (account_id, issuer)
);

-- Owned by helpdesk (accounts). One started OIDC sign-in, used once within 10 minutes. Never the
-- state, the nonce, or the browser value: their SHA-256 as lower-case hex.
CREATE TABLE oidc_request (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL,
    state_hash text NOT NULL,
    browser_hash text NOT NULL,
    nonce_hash text NOT NULL,
    code_verifier text NOT NULL,
    app text NOT NULL CHECK (app IN ('CONSOLE', 'PORTAL')),
    next_path text NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT oidc_request_state_hash_key UNIQUE (state_hash)
);
CREATE INDEX oidc_request_created_at ON oidc_request (created_at);
```

- `issuer`는 설정의 `OIDC_ISSUER`가 아니라 ID 토큰의 `iss`(발견 문서의 `issuer`와 같음을 확인한 값)다.
- 칸 이름과 꼴은 앞 표의 것(`password_reset`의 해시 칸 [소스: V5])을 따른다. `start.TimeTest`(시간대 없는 시각 칸 금지)와 `TenantIdRule`이 이 표도 본다.
- `make-sample.py`는 두 표를 SQL로 채운다(API로 만들 수 없다, 그 스크립트의 머리말이 허용 [소스: `make-sample.py` 6줄]).

## 2. 계약 (C-4)

| 경로 | 요청 | 응답 | 오류 |
|---|---|---|---|
| `GET /api/public/sign-in-options` | – | 200 `{oidc: boolean}`, `no-store` | – |
| `GET /api/public/oidc/start?next=` | 쿼리 `next`(선택) | 302(제공자로 또는 로그인 화면으로) | 404(그 앱에 꺼짐) |
| `GET /api/public/oidc/callback?code=&state=&error=` | 쿼리 | 302(보던 화면으로 또는 로그인 화면으로) | 404(그 앱에 꺼짐) |
| `DELETE /api/console/accounts/{id}/oidc-link` | – | 204 | 401, 403, 404 |
| `GET /api/console/accounts`(있음) | – | 줄에 `oidcLinked: boolean` | – |
| `GET /api/console/system/status`(있음) | – | `oidc: {enabled, apps, lastSuccessAt, providerFailures, lastFailure: {kind, at} \| null}` | – |

- 두 OIDC 경로는 브라우저가 페이지로 여는 경로라 JSON 오류를 내지 않고 302로 답한다. 404만 예외다(꺼진 앱). 계약에는 302와 `Location`을 적는다.
- 공개 경로는 CSRF를 받지 않는 `GET`이다. 돌아오기의 로그인 CSRF는 쿠키와 `state`가 막는다(TM-69).
- `/api/console/accounts/**`는 이미 관리자만이다 [소스: `SecurityConfiguration.adminOnly`]. `AdminOnlyContractTest`를 계약 뒤 한 번 돈다.
- 감사 종류 셋을 더한다. C-12 종류 묶음은 `OIDC_LINKED`·`OIDC_UNLINKED`가 "계정", `INVITATION_ACCEPTED`가 "초대"다 [소스: `web/console/src/app/auditWords.ts:14`. 초대 종류는 따로 "초대" 묶음]. 새 묶음은 만들지 않는다(묶음 차례를 `toEqual`로 보는 잠긴 테스트가 있다). 잠긴 테스트 사본 `__tests__/console1b.ts`의 `KIND_GROUPS`(가짜 서버가 모르는 종류를 400으로 거절, `listedKinds`가 `toEqual`)를 C-5 테스트 탭이 맞춘다. 서버 `audit/AuditChainTest`의 `KINDS`가 쓰이는 곳도 C-4 탭이 본다.
- 의존성 둘은 아직 이 PC의 Maven 저장소에 없다. C-4 계약 탭이 받는다(인터넷 됨, 2026-10-19 확인). Boot 4.1의 `spring-boot-security`에는 OAuth2 자동 구성이 없어 [소스: 서브에이전트가 jar의 `AutoConfiguration.imports`를 읽음] 두 jar를 더해도 필터 사슬이 늘지 않는다. 부품 클래스 이름(`RestClientAuthorizationCodeTokenResponseClient`, `OidcIdTokenValidator`)이 Spring Security 7에 있는지는 받은 뒤 본다.
- 새 경로 넷은 잠긴 `boundary/BoundaryList.java`의 `ENTRIES`에 줄이 있어야 하고(`BoundaryListTest`), `EndpointMappingsTest`는 `/api/` 밖 경로와 이름에 `password`·`reset`·`credential`이 든 경로를 막는다. 새 경로 이름은 거기에 걸리지 않는다. 업그레이드 견본 테스트(`deploy/UpgradeHarnessTest`)가 새 표 둘의 행을 본다.
- 설정 키 넷은 이미 `SettingKeys`에 있다 [소스: `SettingKeys.java:33`]. `deploy/compose.yml`이 넘기게 한다.
- 계약 뒤 한 번 돌 것: `project.NoStoreTest`, `ops.CheckOrderTest`, `ops.EndpointMappingsTest`, `boundary.BoundaryListTest`, `boundary.AdminOnlyContractTest`, `ApiPathPrefixTest`.
- 웹: 로그인 화면이 `sign-in-options`를 새로 부른다. 두 앱의 기본 가짜 서버(`console.ts`, `portal.ts`)에 `{oidc: false}` 핸들러를 더한다(빠지면 앞 묶음의 로그인 테스트가 오류로 깨진다, process C-4).

## 3. 시험

- 서버 테스트의 제공자: Testcontainers로 mock-oauth2-server를 띄우는 도움 하나(`support/OidcProvider` 꼴). 앱의 `OIDC_ISSUER`를 컨테이너의 `http://localhost:{포트}/default`로 준다. OIDC를 켠 앱 문맥은 따로 하나다(다른 테스트 클래스와 문맥을 나누지 않게, 문맥 캐시 3 [소스: `main-ops.md` 힙 절]).
- 브라우저 없이 HTTP로 흐름을 돈다: 시작 → 302의 `Location`을 제공자에 POST(`username`, `claims`) → 제공자의 302에서 `code`, `state` → 같은 쿠키로 돌아오기.
- "토큰이 틀림"(서명, `aud`, `nonce`)은 mock-oauth2-server로 만들기 어렵다. 순수 계산 `IdTokenClaims`의 단위 테스트(구현 뒤, process C-5)와 C-7의 일부러 틀린 구현으로 본다. `aud`와 `iss`가 다른 토큰은 제공자의 다른 발급자 경로(`/other`)로 만들 수 있다 [추론].
- 숨긴 E2E는 `popdesk-holdout`에 HTTP로 쓴다(제공자 컨테이너 하나).
- 순서: 계약 → 테스트(서버, 웹) → 점검 → 구현(서버, 웹) → 검증. 10b와 같다.
