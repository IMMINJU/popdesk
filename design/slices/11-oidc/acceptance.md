# 11 묶음 수용 기준: OIDC 로그인

> `roadmap.md` 11번(AUTH-6)이다. 묶음이 끝나면 `design/archive/11-oidc/`로 간다(D-27).
> 전체 경로다: 공개 계약(설정 키 넷의 동작, 새 공개 경로 셋, 계정 응답의 새 칸, 새 관리 경로, 시스템 상태의 새 칸), 마이그레이션(OIDC 묶음 표, 로그인 요청 표), 개인정보 흐름(제공자의 `iss`·`sub`를 저장하고 익명화 때 지움), 권한과 세션(새 로그인 길). 사전 부검은 같은 폴더의 `premortem.md`다.
> 규칙 ID의 글은 같은 폴더 `rules.md`에 있다. 기준 ID는 `S11-AC-nn`이다.
> 관계있는 결정(`adr.md`): D-15, D-18, D-40, D-43, D-46, D-51, D-64·D-65·D-66(새). 위협 모델: TM-10, TM-15, TM-47, TM-69(새).
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-19). 규칙에 없던 것을 메인이 넣은 곳은 5절에 모았다.

## 1. 넣는 것과 미루는 것

지금: 설정 키 `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, `OIDC_APPS`는 `SettingKeys`와 `SecretFiles`의 목록과 `configuration.md`의 두 줄에만 있고 읽는 코드가 없다 [소스]. 의존성에 OAuth2 클라이언트가 없다 [소스: `server/pom.xml`]. 로그인은 비밀번호뿐이고 세션은 `Sessions.start`가 만든다 [소스].

| 기능 | 11에 넣는 것 | 미루는 것 |
|---|---|---|
| AUTH-6 | 설정 검사, 로그인 시작과 돌아오기, 계정 맞추기와 묶기, 수락 전 계정 켜기, 실패 화면, 관리자의 묶음 풀기, 익명화 때 묶음 지우기(PII-4에서 옮김) | 비밀번호 로그인 끄기(D-65), 제공자의 로그아웃(RP-initiated logout), `userinfo`에서 이메일 읽기, 제공자 둘 이상 |
| OPS-3 | 시스템 상태(C-18)에 OIDC 줄 | OIDC 실패의 운영 경보(OPS-1) |
| 화면 | 로그인(C-1, P-1)의 단추와 실패 문장, 구성원 목록(C-10)의 "OIDC 연결" 표시와 풀기, C-18 | – |

## 2. 수용 기준

말의 뜻:

- "OIDC가 켜진 앱"은 `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`이 모두 있고 `OIDC_APPS`가 그 앱을 담은 것이다(`console`이면 콘솔만, `portal`이면 포털만, `both`면 둘 다. 없으면 `console`).
- "묶음"은 계정 하나와 제공자의 (`iss`, `sub`) 한 쌍이다.
- "로그인 요청"은 로그인 시작이 만든 한 번 쓰는 기록(`state`, `nonce`, PKCE 검증값, 앱, 돌아갈 화면)이다. 10분 동안 쓸 수 있다.
- "로그인 화면으로 돌려보냄(`X`)"은 그 앱의 `/login?oidc=X`로 가는 302다. `X`는 `failed`, `unavailable`, `busy` 가운데 하나다.

### 2.1 설정과 시작

| ID | 기준 |
|---|---|
| S11-AC-01 | If `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`(또는 `OIDC_CLIENT_SECRET_FILE`, D-46) 가운데 일부만 있거나, `OIDC_APPS`가 `console`·`portal`·`both`가 아니거나, `OIDC_ISSUER`가 쿼리·조각 없는 절대 `http(s)` 주소가 아니면, then the system shall 뜨지 않고(프로세스가 끝남) 그 키와 까닭을 로그에 남긴다. 비밀값은 남기지 않는다. 발견 문서 주소를 만들 때는 끝의 `/`를 떼지만, 토큰과 발견 문서의 `issuer`는 설정 값 그대로와 견준다. (AUTH-6, 5절 14) |
| S11-AC-02 | When OIDC 설정이 맞게 있으면, the system shall 제공자에 접속하지 않고 뜬다. 제공자가 멈춰 있어도 앱이 뜨고 비밀번호 로그인이 된다. `OIDC_ISSUER`가 `http`이면 시작할 때 경고 로그를 남긴다(D-51과 같은 꼴). (AUTH-6, 5절 2) |
| S11-AC-03 | When 로그인 화면이 `GET /api/public/sign-in-options`를 부르면, the system shall 그 호스트의 앱에 OIDC가 켜졌는지를 `{oidc: true\|false}`로만 답한다(제공자 주소, 클라이언트 ID는 넣지 않는다). `no-store`다. (AUTH-6) |

### 2.2 로그인 시작

| ID | 기준 |
|---|---|
| S11-AC-04 | When 브라우저가 OIDC가 켜진 앱에서 `GET /api/public/oidc/start`를 열면, the system shall 로그인 요청을 만들고 제공자의 인가 주소로 302를 준다. 쿼리는 `response_type=code`, `client_id`, `redirect_uri`(그 앱의 공개 주소 + `/api/public/oidc/callback`), `scope=openid email profile`, `state`, `nonce`, `code_challenge`, `code_challenge_method=S256`이다. 응답은 쿠키(https면 `__Host-POPDESK_OIDC`에 `Secure`, http면 `POPDESK_OIDC`. 둘 다 `HttpOnly`, `SameSite=Lax`, 경로 `/`, 호스트 전용, 10분)를 심어 이 브라우저와 요청을 묶는다. `no-store`다. (AUTH-6, TM-69, 5절 14) |
| S11-AC-05 | Where 시작 주소에 `next`가 있으면, the system shall 그 값이 `/`로 시작하고 `//`·`/\`로 시작하지 않으며 제어 문자가 없고 512자 이하일 때만 로그인 요청에 담는다. 아니면 버리고 `/`로 본다. (AUTH-6, 5절 7) |
| S11-AC-06 | If 그 앱에 OIDC가 켜지지 않았으면, then the system shall 시작·돌아오기 두 경로 모두 404로 답한다. (AUTH-6) |
| S11-AC-07 | If 제공자 정보(`{issuer}/.well-known/openid-configuration`)를 받지 못하거나 맞지 않으면(연결 3초·읽기 10초 넘김, 200이 아님, 받은 `issuer`가 설정과 다름, 설정이 `https`인데 인가·토큰·JWKS 주소 가운데 `https`가 아닌 것이 있음), then the system shall 로그인 요청을 만들지 않고 로그인 화면으로 돌려보낸다(`unavailable`). 받은 정보는 1시간 쓴다. 받지 못한 일은 30초 기억해, 그동안의 시작은 제공자에 다시 접속하지 않고 바로 `unavailable`이다(제공자가 멈춘 동안 요청 스레드가 묶여 비밀번호 로그인까지 막히지 않게). (AUTH-6, 5절 2, 5절 14) |
| S11-AC-08 | If 같은 클라이언트 IP가 60초에 20번 넘게 시작하거나, 10분이 지나지 않은 로그인 요청이 설치 전체에 10,000개를 넘으면, then the system shall 로그인 요청을 만들지 않고 로그인 화면으로 돌려보낸다(`busy`). (AUTH-6, TM-10, 5절 14) |

### 2.3 돌아오기

| ID | 기준 |
|---|---|
| S11-AC-09 | When 제공자가 `GET /api/public/oidc/callback?code=…&state=…`로 돌려보내고 로그인 요청과 쿠키가 맞고 계정이 맞으면(2.4), the system shall 토큰 주소에서 코드를 바꾸고(`client_secret_basic`, PKCE 검증값), ID 토큰을 확인하고, 새 세션을 시작하고(세션 ID와 CSRF 토큰이 바뀜, S0-AC-17), 로그인 요청에 담은 화면(없으면 `/`)으로 302를 준다. 로그인 요청은 지워져 다시 쓰지 못한다. (AUTH-6) |
| S11-AC-10 | The system shall ID 토큰을 이렇게 확인한다: 제공자 `jwks_uri`의 키로 서명(RS256, ES256만. `none`, HS256 같은 다른 알고리즘은 거절), `iss`가 설정과 같음, `aud`에 클라이언트 ID가 있음, `azp`가 있으면 클라이언트 ID와 같음(`aud`가 여럿이면 `azp`가 꼭 있어야 함), `exp`가 지나지 않음, `iat`가 미래가 아님, `nonce`가 로그인 요청의 것과 같음. 시계 어긋남은 60초까지 받는다(주입한 `Clock`). 모르는 `kid`면 키를 한 번 다시 받는다. (AUTH-6, TM-69) |
| S11-AC-11 | If 쿠키가 없거나, 쿠키와 `state`가 같은 로그인 요청을 가리키지 않거나, 로그인 요청이 없거나 10분이 지났거나 다른 앱의 것이면, then the system shall 토큰 주소를 부르지 않고 세션을 만들지 않고 로그인 화면으로 돌려보낸다(`failed`). (AUTH-6, TM-69) |
| S11-AC-12 | If 제공자가 `error`로 돌려보내고 S11-AC-11의 확인을 지나면(사용자가 취소함 등), then the system shall 그 로그인 요청을 지우고 쿼리 없이 로그인 화면으로 302를 준다. 확인을 지나지 못하면 S11-AC-11대로 `failed`다. (AUTH-6, 5절 9) |
| S11-AC-13 | If 토큰 주소에 닿지 못하거나(연결 3초, 읽기 10초) 5xx·429로 답하면, then the system shall 로그인 화면으로 돌려보낸다(`unavailable`). If 토큰 주소가 그 밖의 오류(4xx)로 답하거나 ID 토큰이 S11-AC-10을 지키지 않으면, then the system shall 로그인 화면으로 돌려보낸다(`failed`). 두 경우 모두 세션을 만들지 않고 로그인 요청을 지운다. (AUTH-6) |
| S11-AC-14 | When 로그인 요청을 새로 만들면, the system shall 10분이 지난 로그인 요청을 지운다(따로 정리 작업을 두지 않는다). (AUTH-6, 5절 6) |

### 2.4 계정 맞추기

| ID | 기준 |
|---|---|
| S11-AC-15 | Where ID 토큰의 (`iss`, `sub`)에 묶음이 있으면, the system shall 그 묶음의 계정으로만 맞춘다. 토큰의 `email`이 다르거나 `email_verified`가 거짓이어도 그렇다. (AUTH-6, TM-15) |
| S11-AC-16 | Where 묶음이 없으면, the system shall ID 토큰의 `email_verified`가 JSON 참(`true`)이고 `email`이 있고 ASCII로만 되어 있을 때만, 그 이메일(ASCII 대소문자 무시, 앞뒤 공백 뺌)의 계정에 맞추고 (`iss`, `sub`)를 그 계정에 묶는다. 그 계정에 같은 `iss`의 다른 `sub` 묶음이 이미 있으면 맞추지 않는다. 이메일, 상태, 역할, 초대 기한은 잠근 계정 행에서 다시 본다(그 사이 관리자가 이메일을 바꾸거나 익명화한 계정에 묶지 않게). 계정을 새로 만들지 않는다. (AUTH-6, RULE-07, TM-15, 5절 14) |
| S11-AC-17 | If 맞춘 계정이 없거나, 정지(익명 계정과 취소한 초대 포함)이거나, 역할이 그 앱의 것이 아니거나(AUTH-1), 수락 전인데 초대가 7일이 지났거나, 묶음이 없는데 `email_verified`가 참이 아니거나 `email`이 없거나 ASCII가 아니거나, S11-AC-16의 다른 `sub`가 있으면, then the system shall 같은 로그인 화면(`failed`)으로 돌려보내고 묶음을 만들지 않는다. 화면과 응답으로 까닭을 가르지 않는다. (AUTH-6, TM-10) |
| S11-AC-18 | When 수락 전(`INVITED`) 계정이 7일 안의 초대로 맞춰지면, the system shall 그 계정을 `ACTIVE`로 바꾸고 초대 링크가 더는 쓰이지 않게 하고 로그인시킨다. 비밀번호는 정하지 않는다. (AUTH-6, 5절 4) |
| S11-AC-19 | The system shall OIDC 로그인에서 비밀번호 잠금(S0-AC-23)을 보지 않고, 비밀번호 실패 횟수를 바꾸지 않는다. (AUTH-6, 5절 5) |
| S11-AC-20 | When OIDC로 같은 계정을 같은 때에 두 번 처음 묶으면, the system shall 묶음을 하나만 남기고 둘 다 로그인시키거나 하나를 `failed`로 보낸다(어떤 한 순서로 한 것과 같다). 500이 나지 않는다. (AUTH-6, STATUS-7의 꼴) |

### 2.5 세션과 비밀번호

| ID | 기준 |
|---|---|
| S11-AC-21 | The system shall OIDC로 시작한 세션을 비밀번호로 시작한 세션과 같게 다룬다: 8시간, 정지·역할 바뀜·비밀번호 바뀜이면 끝남(S0-AC-26, S1A-AC-02), 로그아웃. (AUTH-6) |
| S11-AC-22 | Where 계정에 비밀번호가 없으면(OIDC로 수락함), the system shall 비밀번호 로그인을 일반 실패(AUTH-2, 같은 401)로 답하고, 비밀번호 찾기(AUTH-7)로 비밀번호를 정하게 한다. 정한 뒤에는 두 길 모두로 로그인된다. (AUTH-6) |
| S11-AC-23 | Where OIDC로 수락해 비밀번호가 없는 계정을 관리자가 정지했다가 다시 켜면, the system shall 수락한 계정으로 보고 켠다(`INVITATION_NOT_ACCEPTED`를 내지 않는다). (AUTH-6, 5절 4) |

### 2.6 감사와 로그

| ID | 기준 |
|---|---|
| S11-AC-24 | When OIDC로 로그인하면, the system shall 감사 `SIGN_IN_SUCCEEDED`(바뀐 값 `{method: "OIDC"}`)를 남긴다. 처음 묶으면 그 앞에 `OIDC_LINKED`(대상 그 계정, 바뀐 값 `issuer`)를, 수락 전 계정을 켰으면 `OIDC_LINKED` 뒤에 `INVITATION_ACCEPTED`(바뀐 값 `{method: "OIDC"}`)를 남긴다. 맞춘 계정이 있는데 S11-AC-17로 실패하면 `SIGN_IN_FAILED`(바뀐 값 `{method: "OIDC"}`)를 남긴다. 감사 값에 `sub`, 토큰, 코드는 넣지 않는다. (AUTH-6, AUDIT-1, 5절 8) |
| S11-AC-25 | The system shall 로그에 코드, `state`, `nonce`, PKCE 값, 토큰, 클라이언트 비밀, `sub`를 남기지 않고, 이메일은 첫 글자 뒤로 가린다. 실패 로그는 계정 ID(있으면)와 까닭 이름만 남긴다. 제공자가 준 `error`는 `[a-z_]{1,64}`에 맞으면 남기고(맞지 않으면 그 값 없이 "모르는 꼴"로 남김) `error_description`은 남기지 않는다. (PII-2, TM-69) |

### 2.7 관리

| ID | 기준 |
|---|---|
| S11-AC-26 | When 관리자가 `GET /api/console/accounts`를 부르면, the system shall 줄마다 `oidcLinked`(묶음이 있는지)를 준다. (AUTH-6) |
| S11-AC-27 | When 관리자가 `DELETE /api/console/accounts/{id}/oidc-link`를 부르면, the system shall 그 계정의 묶음을 모두 지우고 그 계정의 세션을 모두 끝내고 204로 답하고 감사 `OIDC_UNLINKED`(대상 그 계정)를 남긴다. 묶음이 없으면 404다. 다음 OIDC 로그인은 S11-AC-16대로 다시 묶는다. (AUTH-6, 5절 3) |
| S11-AC-28 | The system shall 묶음 풀기를 관리자만 하게 한다: 상담원 403, 로그인 없음 401, 고객 호스트 404(D-43), CSRF 없는 쓰기 403, 없는 계정 404. 익명 계정은 묶음이 없으므로 404다. (AUTH-6, 권한) |
| S11-AC-29 | When 관리자가 계정을 익명화하면(PII-4), the system shall 같은 트랜잭션에서 그 계정의 묶음을 지운다. 같은 이메일로 다시 초대한 사람은 OIDC로 옛 계정에 들어가지 않는다. 감사 `OIDC_UNLINKED`는 따로 남기지 않는다(`ACCOUNT_ANONYMIZED`가 남는다). (PII-4, AUTH-6) |
| S11-AC-30 | When 관리자가 `GET /api/console/system/status`를 부르면, the system shall `oidc`에 켜짐 여부, 켜진 앱 목록, 마지막 OIDC 로그인 성공 시각, 앱이 뜬 뒤 제공자 쪽 실패 수(정보 받기, 토큰 바꾸기, ID 토큰 확인), 마지막 실패의 종류(`DISCOVERY`, `TOKEN`, `ID_TOKEN` 가운데 하나 또는 `null`)와 시각을 준다. 메모리에서 세며 앱이 다시 뜨면 비어 있다. 계정 쪽 실패(S11-AC-17)와, 기억해 둔 발견 실패로 제공자에 접속하지 않고 거절한 시작은 세지 않는다. 시각과 30초 기억은 주입한 `Clock`으로 잰다. (OPS-3, 5절 10) |

### 2.8 화면

| ID | 기준 |
|---|---|
| S11-AC-31 | Where 그 앱에 OIDC가 켜져 있으면, the system shall 로그인 화면(C-1, P-1)의 로그인 폼 아래에 "회사 계정으로 로그인" 단추를 보이고, 누르면 `/api/public/oidc/start`로 페이지를 옮긴다(`next`가 있으면 넘긴다. 폼 제출이 아니라 주소 옮기기다. CSP `form-action 'self'`가 제공자로 가는 302를 막기 때문이다). 꺼져 있거나 `sign-in-options`를 읽지 못하면 단추가 없고 비밀번호 로그인은 그대로다. (AUTH-6) |
| S11-AC-32 | When 로그인 화면이 `oidc=failed`·`unavailable`·`busy`로 열리면, the system shall 폼 위에 그 문장(`screens.md`)을 오류로 보인다. 모르는 값이면 보이지 않는다. (AUTH-6) |
| S11-AC-33 | When 관리자가 C-10 구성원 목록을 보면, the system shall OIDC가 묶인 계정의 줄에 "OIDC 연결" 표시를 보이고, 그 줄의 메뉴에 "OIDC 연결 풀기"를 둔다. 확인 대화상자 뒤 풀고 표시를 지운다. (AUTH-6) |
| S11-AC-34 | When 관리자가 C-18 시스템 상태를 열면, the system shall OIDC 구역에 켜짐 여부와 켜진 앱, 마지막 성공 시각, 제공자 실패 수와 마지막 실패(종류 문장, 시각)를 보인다. 꺼져 있으면 "꺼짐"만 보인다. (OPS-3) |

### 2.9 마이그레이션, 견본, 문서

| ID | 기준 |
|---|---|
| S11-AC-35 | The system shall V24로 묶음 표(`oidc_link`)와 로그인 요청 표(`oidc_request`)를 더한다. 둘 다 `tenant_id`가 있고(D-18), 묶음은 (`issuer`, `subject`)와 (`account_id`, `issuer`)가 각각 하나뿐이다. (AUTH-6) |
| S11-AC-36 | The system shall 업그레이드 견본이 묶음 하나와 로그인 요청 하나를 두고(API로 만들 수 없어 SQL로), 견본으로 올린 설치에서 그 계정의 `oidcLinked`가 참이다. |
| S11-AC-37 | The system shall `docs/operate/configuration.md`에 OIDC 절(제공자에 등록할 돌아오기 주소 둘, `OIDC_APPS`, 제공자가 ID 토큰에 `email`과 `email_verified`를 넣어야 한다는 것과 넣지 않는 제공자, `http` 경고, 시계 맞추기), `docs/operate/privacy.md`에 저장하는 `iss`·`sub`와 익명화 때 지움, `docs/use/permissions.md`에 묶음 풀기 줄을 적는다. `deploy/compose.yml`이 키 넷을 앱에 넘긴다. 릴리스 노트에 새 설정과 "비밀번호 로그인은 그대로 남는다"를 적는다. (AUTH-6, PII-1) |

## 3. 센 검증을 받는 기준

| 기준 | 까닭 |
|---|---|
| S11-AC-04, 09~13, 15~20 | 권한과 세션(남의 계정에 들어가기, 로그인 CSRF, 코드 가로채기, 되쓰기) |
| S11-AC-05 | 다른 곳으로 보내기(열린 리다이렉트) |
| S11-AC-22, 23, 29 | 계정 상태와 개인정보(익명 계정, 비밀번호 없는 계정) |
| S11-AC-26~28 | 권한(관리자만) |
| S11-AC-35, 36 | 마이그레이션 |
| S11-AC-04, 09, 15~18, 24, 27, 29 | 숨긴 E2E로 본다: 시험 제공자 하나, 콘솔과 포털, 처음 묶기와 다시 로그인, 제공자 쪽 이메일 바꾸기, 확인 안 된 이메일, 수락 전 계정 켜기, 묶음 풀고 다시 묶기, 익명화 뒤 같은 이메일 재초대 |

일부러 틀린 구현(C-7 점검 지시서에 넣음): `state`를 쿠키와 견주지 않음, 로그인 요청을 지우지 않아 두 번 쓰임, `nonce`를 보지 않음, `aud`를 보지 않음, `azp`가 다른 클라이언트인 토큰을 받음, 서명을 보지 않음(`alg: none` 받음), HS256을 클라이언트 비밀로 확인함, 묶음이 있어도 이메일로 맞춤, `email_verified`를 보지 않음, 문자열 `"true"`를 참으로 봄, 다른 `sub`로 묶음을 덮어씀, 정지 계정을 로그인시킴, 상담원 계정을 포털에 로그인시킴, `next`에 `//evil.example`을 받음, 익명화가 묶음을 남김, 묶음 풀기를 상담원에게 엶, 묶음을 풀어도 세션을 둠, 잠그기 전 읽은 이메일로 묶음, 수락 전 계정의 초대 기한을 보지 않음.

순수 계산 클래스(C-8 목록 후보): `SafeNext`(시작 주소의 `next` → 받을 값 또는 `/`), `OidcSettings`(환경 값 → 켜진 앱 또는 문제 목록), `IdTokenCheck`의 클레임 판정(서명을 뺀 클레임과 지금 시각, 기대값 → 통과 또는 까닭). C-10에서 PIT를 받는다.

## 4. 화면

`screens.md`다. C-1·P-1 로그인의 단추와 문장, C-10의 표시와 메뉴와 대화상자, C-18의 OIDC 구역.

## 5. 메인이 정한 것

| # | 정한 것 | 까닭 | 다른 길 |
|---|---|---|---|
| 1 | OIDC를 켜도 비밀번호 로그인과 비밀번호 찾기를 끄지 않는다. 끄는 설정은 두지 않는다(D-65, 위협 모델 6절의 정할 것을 닫음) | 제공자가 멈추거나 클라이언트 비밀이 만료되면 들어올 길이 비밀번호뿐이다(프로젝트 사전 부검 130줄). 첫 관리자 복구 명령은 컨테이너 안에서만 된다. SSO만 쓰게 하는 요구는 아직 없다 | `OIDC_ONLY=true`로 상담 쪽 비밀번호 끄기 |
| 2 | Spring Security의 OAuth2 클라이언트·JOSE 부품을 쓰되 `oauth2Login()`과 Boot의 자동 구성은 쓰지 않고, 시작과 돌아오기를 직접 짠다(D-64). 제공자 정보는 처음 시작할 때 받아 1시간 쓴다 | 자동 구성은 뜰 때 제공자에 접속해 멈춘 제공자가 앱을 못 뜨게 한다(사전 부검 130줄). `oauth2Login()`은 서블릿 세션에 요청을 두는데 이 앱은 세션 관리를 끄고 로그인 때만 세션을 만든다[소스: `SecurityConfiguration.java`의 `sessionManagement` 끔, `Sessions.start`] | `oauth2Login()` / Nimbus OIDC SDK 직접 |
| 3 | 관리자가 묶음을 풀 수 있게 하고, 풀면 그 계정의 세션을 끝낸다(새 경로) | 제공자에서 사람을 다시 만들면 `sub`가 바뀌어 그 사람은 OIDC로 영영 못 들어온다. 이메일로 다시 묶는 길이 있어야 한다. 잘못 묶였거나 제공자 계정을 뺏겨 푸는 때가 많으므로 남은 세션도 끝낸다(보안 검토) | 묶음을 고칠 길 없음 / 익명화 뒤 재초대 / 세션은 두기 |
| 4 | 수락 전 계정은 초대가 7일 안일 때만 OIDC로 켠다. 켜면 비밀번호 칸에 "관리자 초기화"와 같은 쓸 수 없는 값을 둔다 | 초대 링크와 같은 기한을 지킨다. 지난 초대는 관리자가 다시 보낸다. 비밀번호 칸이 비어 있으면 다시 켜기가 "수락 전"으로 보고 거절한다[소스: `AccountService` 다시 켜기의 `INVITATION_NOT_ACCEPTED`] | 기한 없이 켬 / 비밀번호 칸을 비워 둠 |
| 5 | OIDC 로그인은 비밀번호 잠금을 보지 않는다 | 잠금은 비밀번호 맞히기를 막는 것이다. 제공자가 사람을 확인했다. 잠긴 동안 OIDC로 들어올 길을 둔다 | 잠긴 계정은 OIDC도 실패 |
| 6 | 로그인 요청은 DB 표에 두고, 새로 만들 때 10분 지난 것을 지운다. 정리 작업을 더하지 않는다 | 로그인 전이라 세션이 없다. 정리 작업 목록은 잠긴 테스트 셋이 쥐고 있어 바꾸면 그 셋을 맞춰야 한다(10b 설계 0절 7). 남는 행은 마지막 시작 뒤 10분치뿐이다 | 서명한 쿠키(서명 키 관리가 생김) / 정리 작업 |
| 7 | 시작 주소는 `next`를 받아 로그인 뒤 그 화면으로 보낸다. 같은 앱 안의 경로만 받는다 | 지금 로그인 화면이 `next`를 쓴다[소스: `C1Login.tsx`의 `safeNext`]. OIDC로 들어와도 보던 화면으로 가야 한다 | 늘 `/` |
| 8 | 감사: 새 종류 `OIDC_LINKED`, `OIDC_UNLINKED`, `INVITATION_ACCEPTED`. 로그인 성공·실패는 있는 종류에 `method`를 단다 | 지금 초대 수락은 감사를 남기지 않는다[소스: `InvitationService.accept`]. OIDC로 켜는 일은 비밀번호 없이 계정이 열리는 일이라 남긴다. 비밀번호 수락에도 남길지는 이 묶음에서 바꾸지 않는다(5절 11) | 수락은 남기지 않음 |
| 9 | 제공자에서 취소하면 문장 없이 로그인 화면으로 간다 | 사람이 고른 일이라 오류가 아니다 | `failed` 문장 |
| 10 | 시스템 상태의 OIDC 수는 메모리에서 센다. 운영 경보는 두지 않는다 | 비밀번호 로그인이 남아 있어 OIDC 장애가 곧 접속 불가가 아니다. 정리 작업처럼 DB에 남길 까닭이 없다 | DB에 남김 / 한 시간 실패면 경보 |
| 11 | 비밀번호로 초대를 수락할 때 `INVITATION_ACCEPTED`를 남기는 것은 넣지 않는다 | 잠긴 앞 묶음 테스트가 수락 뒤 감사 줄 수를 볼 수 있다. 넘긴 일로 둔다 | 같이 넣음 |
| 12 | ID 토큰에서만 `email`, `email_verified`를 읽는다. `userinfo`는 부르지 않는다 | 흔한 제공자(Google, Keycloak, Okta, Auth0)는 `email` 범위면 ID 토큰에 넣는다. Microsoft Entra ID는 `email_verified`를 주지 않아 처음 묶기가 안 된다. 운영 문서에 적고 넘긴 일로 둔다 | `userinfo`로 채움 / 설정으로 확인 생략 |
| 13 | 시험 제공자는 Keycloak 대신 `ghcr.io/navikt/mock-oauth2-server:2.1.10`이다(D-66) | 이 PC에서 3초에 뜨고 117MB를 쓴다. 로그인마다 `sub`와 클레임을 테스트가 정한다(대화형 로그인 폼에 `username`, `claims` JSON을 보냄, 2026-10-19 메인이 띄워 확인). 실제 OIDC 서버(발견 문서, JWKS, 서명한 토큰)라 mock 금지(`check-test-style.py`)에 걸리지 않는다. 클라이언트 비밀을 보지 않아 "비밀이 틀림"은 시험하지 못한다 | Keycloak(무겁고 클레임을 사람마다 정해야 함) |
| 14 | 보안 검토(2026-10-19, 서브에이전트)에서 받은 것: 발견 실패 30초 기억과 연결 3초, 로그인 요청 전체 상한 10,000과 `next` 512자, `azp`가 있으면 늘 확인, 알고리즘 RS256·ES256만, https에서 `__Host-` 쿠키, 잠근 행에서 다시 보기, 묶음 풀기에 세션 끝내기, OIDC 이메일은 ASCII만(PostgreSQL `lower()`가 켈빈 기호 같은 글자를 ASCII로 접는다), `error`도 `state` 확인 뒤, 토큰 주소 5xx·429는 `unavailable`, 발견 주소의 끝 `/`, https 발급자의 http 주소 거절, 단추는 주소 옮기기, 제공자 `error` 글은 꼴을 본 뒤에만 로그 | OIDC Core 3.1.3.7, RFC 9700의 권고와 이 앱의 CSP·세션 꼴에 맞춘다. IPv6를 /64로 묶어 세는 것은 `IpRateLimit` 전체의 일이라 넣지 않았다(넘긴 일) | 검토 전 판 |
| 15 | C-5 테스트 탭 질문: 흐름 테스트는 토큰 시각을 앱 시계로 맞춰 보낸다. 대소문자만 다른 이메일은 맞춘다. 발견 실패(S11-AC-07)와 `DISCOVERY` 수(S11-AC-30)는 닫힌 포트 앱의 `unavailable`만 HTTP로 보고 나머지는 단위 테스트와 C-7이 본다. 전체 상한 10,000(S11-AC-08)은 HTTP로 보지 않는다. 흐름 도움 `OidcAppTest.account()`는 계정 목록에서 찾는다 | 공용 테스트 시계가 실제와 11일 다르다. 발견 문서 1시간 기억과 실패 30초 기억이 같은 컨텍스트의 다른 테스트로 번진다. 상한을 채우면 10분 동안 그 컨텍스트의 모든 시작이 `busy`가 된다. 콘솔에 계정 하나를 읽는 경로가 없다 | 컨텍스트 더 만들기 |
| 16 | 웹: 로그인 단추 조각은 `@popdesk/app`의 `OidcSignIn`(props `next`, `navigate`)이다. 포털 로그인의 넘침 story는 두지 않고 같은 조각을 쓰는 C-1 story로 갈음한다. C-18이 꺼짐이면 "꺼짐"만 보이고 아래 설명도 없다 | jsdom은 `location.assign`의 주소를 남기지 않아 조각 테스트가 옮기는 함수를 넘겨 본다. story 실행기는 콘솔 화면만 잡고 콘솔은 포털을 가져오지 못한다(D-57) | 포털 story 자리 만들기 |
| 17 | C-7 서버 점검: 서명·알고리즘·`azp`·`kid`, 발견 실패 기억, `DISCOVERY` 수, 한 앱만 켠 설정은 테스트 안에서 ID 토큰을 직접 만들어 주는 작은 OIDC 서버(`support/`, JDK `HttpServer`, 웹훅 받는 쪽과 같은 실제 대역)와 그것을 쓰는 둘째 OIDC 컨텍스트(`OIDC_APPS=console`)로 HTTP에서 본다. 돌아오기 302의 `no-store`와 실패 갈래의 로그도 더한다. `BoundaryList`의 묶음 풀기 줄은 `OidcUnlinkAccessTest`를 가리킨다 | 시험 제공자는 늘 바른 토큰만 만들고, 단위 테스트 `IdTokenClaims`는 서명을 보지 않는다. 서명은 센 검증을 받는 기준이라 HTTP 테스트가 있어야 한다 | 디코더 단위 테스트만 |
