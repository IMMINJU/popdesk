# 9번 묶음 설계

> `acceptance.md`의 기준을 어떻게 만드는지 적는다. 조사 메모 `~\.claude\popdesk\notes\s9-survey.md`.
> 상태: **메인이 정함, 사용자 확인 전**(2026-10-17). C-1~3 검토(crosscheck 서브에이전트 둘: 서버, 웹)를 반영했다(검토 글은 메인 세션 스크래치 `9-review-server.md`, `9-review-web.md`).

## 0. 메인이 정한 것

| # | 정한 것 |
|---|---|
| 1 | 서버 패키지 `helpdesk.branding`: `BrandingService`(읽기, 저장, 로고), `BrandingRepository`(DB를 읽는 곳은 여기만, `ScopedReadRulesTest`), 순수 계산 `AccentPalette`. 컨트롤러는 `web/publicapi/PublicBrandingController`, `web/console/ConsoleBrandingController`. 메일 쪽은 `notification`에 작은 인터페이스 `BrandingName`을 두고 `helpdesk.branding`이 구현한다(`notification`은 `helpdesk`를 가져오지 않는다. 새 순환 없음) |
| 2 | 이미지 디코딩: 새 패키지 `helpdesk.image`에 공개 클래스 `ImageDecoder`(상자 가로·세로를 인자로, EXIF 방향대로 돌린 **뒤**의 크기로 상자에 맞춤, 서브샘플링도 상자를 따름)와 빈 `ImageDecoding`(동시 디코딩 둘의 세마포어, 지금 `PhotoService`의 필드에서 옮김)을 둔다. `helpdesk.photo`의 `PhotoHeader`·`PhotoImages`는 이름과 시그니처를 그대로 두고 안에서 `ImageDecoder`를 부른다(잠긴 `PhotoImagesTest`가 package-private 멤버를 쓴다). 사진의 동작과 오류는 그대로다 |
| 3 | 요청 수 제한은 `AccountRateLimit.Action.BRAND_LOGO(10)`(1분 창, 사진과 따로). 사진처럼 크기 검사보다 먼저 센다 |
| 4 | 공개 브랜딩은 요청마다 DB에서 읽는다(행 하나, 로고는 지문과 테마만). 메모리 캐시는 두지 않는다 |
| 5 | 웹 `@popdesk/app/branding`: `BrandingProvider({ load, children })`, `useBranding()`, `brandingStyle()`, `parseBranding()`, `PublicBranding` 타입. 앱이 `load`로 공개 클라이언트 함수를 넘긴다(`@popdesk/app`은 API 클라이언트를 모른다, `useUnreadCount`와 같은 꼴). `TEXT.displayName`은 기본값으로 남는다 |
| 6 | `AppShell`에 선택 prop `logo?: { light: string; dark: string }`(공용 컴포넌트 API, 전체 경로). 없으면 지금의 강조색 네모다 |
| 7 | 메일: `MailSender.attempt`가 보낼 때 표시 이름을 한 번 읽어 `MailText` 세 메시지(`invitation`, `passwordReset`, `test`)의 인자와 보내는 사람 이름으로 같이 넘긴다(`Message`에 `senderName`). 이름은 `MailText.oneLine`을 거친다 |
| 8 | `@popdesk/ui` 새 컴포넌트 `LogoField`. `PhotoField`(둥근 아바타)는 넓히지 않는다 |
| 9 | `@popdesk/ui` 새 컴포넌트 `AuthBrand`(로그인 전 화면의 로고와 표시 이름, props `displayName`, `logo?`). 두 앱의 `.pd-auth-brand` CSS 두 벌(`console.css:661`, `portal.css:335`)을 `ui/styles.css` 하나로 옮긴다. 화면 여덟의 열두 곳을 이것으로 바꾼다 |
| 10 | `useDocumentTitle(title, displayName?)`: 둘째 인자가 없으면 브랜딩 컨텍스트의 이름(Provider가 없으면 "popdesk")을 쓴다. 그러면 부르는 열두 곳(`PageTitle` 둘, `ScreenState` 둘, 로그인 전 여덟)을 고치지 않는다 |
| 11 | 웹 계약 탭(C-4)이 필요하다: 위 5, 6, 8, 9, 10의 API와 story 껍데기, `tokens.css`의 변수 꼴(4절) |

## 1. 표 (V21)

```sql
-- 설치의 브랜딩(BRAND-1). 행은 하나다(id = 1). 기본 행을 여기서 넣는다(V18의 SLA 정책 버전 1과 같은 꼴).
create table branding (
    id smallint PRIMARY KEY CHECK (id = 1),
    tenant_id bigint NOT NULL,
    display_name text NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 50),
    accent text CHECK (accent ~ '^#[0-9A-F]{6}$'),
    version bigint NOT NULL CHECK (version >= 1),
    changed_by bigint REFERENCES account (id),
    changed_at timestamptz NOT NULL
);
INSERT INTO branding (id, tenant_id, display_name, accent, version, changed_by, changed_at)
VALUES (1, 1, 'popdesk', NULL, 1, NULL, now());

-- 테마마다 로고 하나(BRAND-2). 다시 인코딩한 PNG만.
create table branding_logo (
    theme text PRIMARY KEY CHECK (theme IN ('LIGHT', 'DARK')),
    tenant_id bigint NOT NULL,
    png bytea NOT NULL,
    sha256 text NOT NULL,
    width integer NOT NULL CHECK (width BETWEEN 1 AND 512),
    height integer NOT NULL CHECK (height BETWEEN 1 AND 128),
    changed_by bigint NOT NULL REFERENCES account (id),
    changed_at timestamptz NOT NULL
);
```

- 키에 `tenant_id`를 쓰지 않는다(D-18: 아직 tenant로 거르지 않는다). `NOT NULL`은 대문자로(`check-tenant-id.py`의 꼴).
- `changed_at`의 `now()`는 마이그레이션의 기본 행에만 쓴다(앱은 `Clock`). 앞 예: V18의 `sla_policy` 버전 1. `changed_by`는 `sla_policy.changed_by`처럼 계정 외래 키(계정은 익명화해도 행이 남는다). 기본 행은 `NULL`(popdesk 자신).
- 저장: `branding` 행 `for no key update` → 값 검사(400이 먼저) → 버전 견줌(409) → 값이 같으면 끝 → `update`(버전 + 1) → 감사.
- 로고 올리기·지우기: 디코딩(트랜잭션 밖) → 트랜잭션: `branding` 행 `for no key update`(버전은 올리지 않는다. 동시에 온 로고 쓰기를 줄 세워 감사의 "전 값"이 맞게) → 지금 지문 읽기 → 같으면 끝 → `insert … on conflict (theme) do update` 또는 `delete` → 감사.
- 새 잠금은 `FOR NO KEY UPDATE`뿐이다. `TimeTest`(시각 칸은 `timestamptz`), `TenantIdRule`, `check-tenant-id.py`를 지킨다. 서버 구현 탭은 `start` 패키지를 돈다.
- `server/README.md`의 Tables 표에 두 줄.

## 2. 계약 (C-4)

공개(`/api/public/**`, 두 계약 파일 모두에 나온다):

- `GET /api/public/branding` → `PublicBranding { displayName: string, colors: AccentColors | null, logos: { light: string | null, dark: string | null } }`. `no-store`(지금 규칙 그대로). 로고 주소는 `/api/public/branding/logos/{있는 테마}?v={지문}`. 한 테마만 있으면 두 칸이 같은 주소다.
- `GET /api/public/branding/logos/{theme}`(`theme`은 `light`·`dark`. 그 밖은 404) → `image/png`, `nosniff`, 강한 `ETag`(지문), `If-None-Match`면 304. `v`가 그 로고의 지문이면 `public, max-age=31536000, immutable`, 아니면 `no-cache`. 그 테마에 로고가 없으면 404 `NOT_FOUND`(다른 테마 것을 대신 주지 않는다. 대신 쓰는 것은 공개 응답의 주소가 정한다). `HEAD`도 같은 머리글.
- `AccentColors { light: ThemeColors, dark: ThemeColors }`, `ThemeColors { accent, accentHover, onAccent, accentSubtle }`(모두 `#RRGGBB` 대문자).

관리자(`/api/console/branding/**`, `SecurityConfiguration.adminOnly()`에 더한다. 역할 검사가 인자 변환보다 먼저라 상담원은 늘 403):

- `GET /api/console/branding` → `Branding { version, displayName, accent: string | null, colors: AccentColors | null, contrast: { light: number, dark: number } | null, logos: { light: BrandingLogo | null, dark: BrandingLogo | null } }`, `BrandingLogo { url, width, height }`(`url`은 공개 로고 주소).
- `PUT /api/console/branding` ← `UpdateBrandingRequest { version, displayName, accent: string | null }` → `Branding`. 400 `INVALID_INPUT`(`fields[]`: `displayName` `REQUIRED`·`BLANK`·`TOO_LONG`·`INVALID_FORMAT`, `accent` `INVALID_FORMAT`·`LOW_CONTRAST`), 409 `VERSION_CONFLICT`.
- `GET /api/console/branding/accent?color=` → `AccentPreview { colors: AccentColors, contrast: { light: number, dark: number }, acceptable: boolean }`. 400 `color` `REQUIRED`·`INVALID_FORMAT`.
- `PUT /api/console/branding/logos/{theme}`(multipart `file`) → `BrandingLogo`. 413 `PAYLOAD_TOO_LARGE`, 400 `UNSUPPORTED_IMAGE`·`IMAGE_TOO_LARGE`·`file` `REQUIRED`, 429, 503 `SERVER_BUSY`, 테마가 틀리면 404. 사진은 `POST`지만 로고는 테마마다 하나를 바꿔 넣는 것이라 `PUT`이다(`conventions.md`에 적음).
- `DELETE /api/console/branding/logos/{theme}` → 204(없어도 204). 테마가 틀리면 404.

`fields[].code`에 `LOW_CONTRAST`(`conventions.md` 165-178줄 표). 감사 `BRANDING_CHANGED`, `BRANDING_LOGO_CHANGED`. 감사 대상 종류 `BRANDING`을 `AuditTargetType`(감사 목록 `targetType` 필터)과 `AuditEntry` 설명에 더한다. 로고 감사 대상의 `theme`은 `LIGHT`·`DARK`(DB 값과 같은 대문자). 바뀐 값은 늘 `{from, to}`(기본 행이 있어 "처음 정한 값"이 없다).

## 3. 순수 계산 `AccentPalette` (C-8 목록, C-10 PIT)

입력 `#RRGGBB`(대문자로 맞춘 뒤). sRGB ↔ OKLab은 Björn Ottosson의 식(2020), OKLCH는 그 극좌표다. 대비는 WCAG 2.1 상대 휘도. 세제곱근과 거듭제곱은 `StrictMath`(JVM마다 같은 값).

| 값 | 밝은 테마 | 어두운 테마 |
|---|---|---|
| `accent` | 넣은 색 그대로 | 명도 `Ld = min(max(L + 0.265, 0.773), 0.97)`, 채도·색상 그대로 |
| `accentHover` | 명도 `L - 0.08`(단 `L < 0.2`이면 `L + 0.2`) | 명도 `min(Ld + 0.08, 0.97)` |
| `onAccent` | `#FFFFFF` | `#191817` |
| `accentSubtle` | 명도 0.955, 채도 `min(C, 0.035)` | 명도 0.28, 채도 `min(C, 0.05)` |

- 0.265와 0.773: 기본 에메랄드 `#047857`(OKLab L 0.508)과 `#34D399`(0.773)의 차이와 어두운 쪽 명도.
- 색역 밖이면 명도와 색상을 두고 채도만 이분 탐색으로 줄인다(30번, 각 채널 `[0, 1]`을 `1e-9` 여유로 본다). 결과는 채널마다 반올림한 `#RRGGBB`.
- 받기(D-61): 밝은 `accent`가 `#FFFFFF`, `#F7F6F3`, `#F1F0ED`, `#F4F3F0`, 만든 밝은 `accentSubtle` 모두에, 어두운 `accent`가 `#191817`, `#201F1D`, `#2A2826`, `#121110`, `#24221F`, 만든 어두운 `accentSubtle` 모두에 4.5 이상. 대비는 반올림한 hex로 잰다. 응답의 대비는 각 테마의 가장 낮은 값을 소수 둘째 자리에서 내린 것이다(4.499는 4.49, 받지 않음).
- 어두운 쪽은 만든 꼴상 늘 받는다: 명도가 0.773 이상이면 어두운 바탕 무리 위 대비가 6.89 이상이다(서버 검토가 색상 0.1도 간격으로 잼, 메인이 4,096색 격자로 다시 잼: 받은 1,239색 가운데 어두운 쪽 실패 0). 검사 코드는 두되, PIT에서 그 줄의 변이가 살아남으면 이 까닭으로 적는다.
- 메인이 직접 잰 값(검산용, 밝은 쪽은 무리의 가장 낮은 대비): `#047857` → 밝게 4.81(받음), 어둡게 `#6ECBA5` 7.27, 마우스 올림 `#005E43`/`#88E5BF`, 옅은 바탕 `#DBF8EB`/`#0A3123`. `#1E293B` → 12.83, 어둡게 `#A8B6CD`. `#E11D48` → 4.10, 받지 않음(흰 바탕만이면 4.70). `#C2410C` → 4.50, 받음. `#B45309` → 4.38, 받지 않음. `#FACC15` → 받지 않음. `#6D28D9` → 6.21, 어둡게 `#B8A5FF`(어두운 쪽 가장 낮은 대비 6.89).
- 받은 색은 흰 바탕 대비가 5.15 이상이다(격자 최솟값).
- 마우스 올림의 버튼 글자 대비는 `accent`보다 크거나(밝게는 더 어둡고 어둡게는 더 밝다), `L < 0.2` 갈래에서는 14 이상이다. 옅은 바탕 위 본문 글자는 12:1 언저리다.
- 기본(강조색 없음)은 계산하지 않는다. 앱의 `tokens.css` 값이 그대로 쓰인다. 기본 에메랄드 hex를 직접 넣으면 어두운 값이 `#6ECBA5`가 된다(기본의 `#34D399`와 다르다). C-14의 "기본 색 쓰기"는 `null`을 보낸다.
- 단위 테스트에 넣을 경우(주석에 적는다): 위 검산 값, 색역 밖(채도가 큰 빨강·자홍의 어두운 값), 명도 0과 1, `L < 0.2` 갈래, 경계 대비(4.5 바로 아래·위), 소문자 입력, 꼴 틀림.

## 4. 웹

- `tokens.css`의 강조색 넷을 `--accent: light-dark(var(--brand-accent-light, #047857), var(--brand-accent-dark, #34d399));` 꼴로 바꾼다(`accent-hover`, `on-accent`, `accent-subtle`도). 빌드가 `light-dark()`를 낮춰도 바르게 낮춰진다(웹 검토가 lightningcss 1.33.0으로 확인). `[data-theme]`와 `prefers-color-scheme` 둘 다 그대로 따른다.
- `brandingStyle(colors)`: `{ '--brand-accent-light': '#…', '--brand-accent-dark': '#…', … }` 여덟을 돌려주는 순수 함수. `colors`가 `null`이면 빈 객체다.
- `parseBranding(value)`: 공개 응답과 저장소 값의 꼴 검사(이름 1~50자 글자, 색 `#RRGGBB`, 로고 주소가 `/api/public/branding/logos/`로 시작). 틀리면 `null`. 잠긴 story 가짜 서버(`storyFetch.ts`)가 모르는 GET에 목록 꼴 200을 주므로 꼭 필요하다.
- `BrandingProvider`(앱의 `App`에서 `SessionProvider` 바깥): 처음 그릴 때 `localStorage`의 값(`parseBranding`을 지난 것, 없으면 기본)으로 시작하고, `useLayoutEffect`에서 `document.documentElement.style.setProperty`로 여덟 변수를 넣는다(첫 그림 전). 내려갈 때 여덟을 지운다. 마운트 뒤 `load()`를 한 번 부르고, 지나면 저장소에 남기고(try/catch) 바꾼다. 실패하면 그대로다.
- `useBranding()`: `{ displayName, colors, logos, setNameAndColors(next), setLogo(theme, logo) }`. C-14는 이름·색은 저장 답에서만, 로고는 로고 답에서만 바꾼다(늦게 온 답이 다른 쪽을 옛 값으로 되돌리지 않게).
- 로고 그리기(`AppShell`, `AuthBrand`): `<img>` 둘(밝게, 어둡게)을 그리고 CSS가 토큰과 같은 조건(`@media (prefers-color-scheme: dark) { :root:not([data-theme='light']) … }`, `[data-theme='dark'] …`)으로 하나를 숨긴다. 두 주소가 같으면 하나만 그린다. `alt=""`, CSS `max-height` 28px(머리)·40px(로그인 전), `width: auto`.
- 콘솔 머리(펼친 옆 메뉴, 브랜드 자리 156px 언저리): 로고 `max-width` 96px, 이름은 남는 폭에서 말줄임(`min-width: 0`, `overflow: hidden`, `text-overflow: ellipsis`). 접힌 띠(64px)에서는 로고 대신 지금의 강조색 네모. 포털 머리: 로고 `max-width` 140px.
- 포털 번들: 공개 경로만 부른다(`check-portal-bundle.py` 통과).
- C-14: `web/console/src/screens/C14Branding.tsx`, 라우트 `/branding`(`AdminOnly`), 메뉴 끝. `C13SlaPolicy`의 읽기(`useMainBusy`, 뼈대 없음)·저장(화면 안 `Alert`, "바뀐 것이 없습니다.")·409 꼴을 따른다.
- C-12: 감사 종류 둘을 "설정" 묶음에(`auditWords.ts`). 글은 `screens.md` 4절.

## 5. 메일

- `SmtpMail`이 주소를 정하는 것(`from()`)은 그대로다. 보낼 때 그 주소를 `InternetAddress`로 읽어 이름(`personal`)이 없으면 `Message.senderName`을 `setPersonal(name, "UTF-8")`로 넣는다. `MAIL_FROM`에 이름이 있으면 그것이 이긴다.
- `MailSender.attempt`가 글을 만들 때 표시 이름을 한 번 읽어 글과 보내는 이름에 같이 쓴다(대기 행에 이름을 저장하지 않는다. 보낼 때의 이름이 나간다. 메일 하나 안에서는 한 이름이다). 시험 메일(`TestMail`)도 같다.
- `MailText.invitation`, `passwordReset`, `test`가 이름을 인자로 받는다. 문의 메일의 "지원팀"은 그대로다. 샌드박스 머리말(`[sandbox]`)도 그대로다.

## 5a. C-4·C-5에서 맞출 잠긴 테스트

서버:

| 파일 | 까닭 |
|---|---|
| `project/NoStoreTest` | (1) `send()`의 경로 값 바꾸기에 `{theme}`, (2) `withValues`도 같이, (3) `REQUIRED_QUERY`에 `/api/console/branding/accent` → `?color=%23047857`, (4) 로고 경로를 `PHOTO_READS`처럼 세 검사의 예외에. 같은 컨텍스트에 로고가 없다는 것은 `acceptance.md` 6절이 지킨다 |
| `boundary/BoundaryList.java`(경로 목록 본체) | 새 operation 일곱과 그 경계 테스트 이름 |
| `boundary.AdminOnlyContractTest` | 구현 쪽이 `adminOnly()`에 경로를 더하면 그대로 지나는지 계약 탭이 한 번 돈다 |
| `photo/PhotoImagesTest` | 고치지 않는다. 사진 클래스의 시그니처를 두는 까닭(0절 2) |

필요 없다고 확인한 것: `ops.EndpointMappingsTest`(계약에서 자동), `ops.CheckOrderTest`(손으로 쓴 목록, SLA도 없음), `ops.ScopedReadRulesTest`(저장소 밖에서 DB를 읽지 않으면 지남), `TestMailTest` 스냅숏(From 없음, 기본 이름이 같음), `MemberMailTest`, `support/MailServer`.

웹:

| 파일 | 까닭 |
|---|---|
| `web/console/src/screens/adminOnly.1b.test.tsx:14`, `web/console/src/app/layout.inbox.test.tsx:54` | 관리자 메뉴 전체 목록 끝에 "브랜딩" |
| `web/console/src/screens/C12AuditLog.8a.test.tsx:37`, `web/console/src/__tests__/console1b.ts:24-81`(`KIND_GROUPS`), `web/console/src/app/auditWords.words.test.ts:43-83`(`others` 배열) | "설정" 묶음에 새 종류 둘 |
| `web/console/src/__tests__/console.ts`, `web/portal/src/__tests__/portal.ts` | 기본 가짜 서버에 공개 브랜딩(기본 값), `beforeEach`에서 `localStorage.clear()`와 `documentElement`의 `style` 지우기 |
| `web/console/src/__tests__/storyFetch.ts:140-159` | 공개 브랜딩에 기본 답(지금은 모르는 GET에 목록 꼴 200) |
| `web/packages/api/src/testing/contract.test.ts` | 새 스키마 견본 |

그대로 지나는 것(확인): `adminOnly.test.tsx`(`toContain`), 메뉴 붙음을 보는 넷(`C11Trash.7a:114-116`, `C13SlaPolicy.8a:117-119`, `C5Stats.7b:90-92`, `C15MyTasks:100-101`), "popdesk"를 보는 것 모두(기본 이름), 요청 수를 세는 것(경로로 거름).

계약 탭이 C-4 보고에서 이 표를 고친다(`process.md` C-4).

## 6. 견본 (`make-sample.py`)

표시 이름 "Sample Desk", 강조색 `#6D28D9`, 밝은 로고 하나(작은 PNG). 견본 기대값에 공개 응답 셋(이름, 색 `null` 아님, 두 로고 주소가 같은 경로). 0.1.0~0.4.0 견본에는 브랜딩 기대값이 없으니 견본 검사기는 키가 없으면 기본을 기대한다. 바꾸면 deploy 견본 테스트 하나를 이미지로 돈다.

## 7. 문서

- `docs/integrate/conventions.md`: 공개 브랜딩과 로고 캐시, `fields[].code` 표에 `LOW_CONTRAST`, 감사 둘과 대상 `BRANDING`, 로고 `PUT`의 까닭. 사진만 말하는 문구(104-105, 154, 158줄: 2MB 한도, 동시 디코딩 "2 profile photos")에 로고(512KB, 사진과 합쳐 둘)를 더한다.
- `docs/operate/configuration.md`: `MAIL_FROM`에 이름이 없으면 표시 이름이 보내는 이름이 된다. 디코딩 메모리 문단(296줄)에 로고.
- `server/README.md` Tables 표에 두 줄.
- 묶음을 닫을 때 메인: `design/tokens.md` 37줄("9번에서 정한다")을 이 문서 3절로, 254줄(지원팀 표시가 "로고 자리")을 "로고는 머리와 로그인 전 화면에만, 지원팀 표시는 그대로"로.
- 위협 모델 TM-65, TM-66(`threat-model.md`에 넣었다).

## 8. C-4에서 정한 것 (2026-10-17 메인)

`tasks/s9-c4-server-report.md`, `s9-c4-web-report.md`.

- 지문은 다시 인코딩한 PNG의 SHA-256 앞 16자(16진수)다. `ETag`, 로고 주소의 `v`, 감사의 `logo` 값이 모두 이것이다. 표 `sha256`에는 전체를 둔다.
- 순수 계산 클래스 이름은 `AccentPalette`다(계약 record `web.publicapi.AccentColors`와 겹치지 않게).
- `docs/use/permissions.md`의 브랜딩 줄(관리자만)은 C-9 서버 구현 탭이 넣는다.
- 테마가 틀린 404와 로고가 없는 404는 같은 몸이다.
- 공개 `GET /api/public/branding`은 껍데기에서 기본 값을 준다(나머지는 501). 웹 C-4는 `tokens.css` 변수 꼴, `AppShell.logo`, `LogoField`, `AuthBrand`, `@popdesk/app/branding` 껍데기를 넣었다. `.pd-auth-brand` CSS를 `ui`로 모으는 것은 C-9다.
- 시험 메일은 `MailSender.attempt`를 거치지 않는다(`TestMail`이 만든다). 거기서도 같은 꼴로 이름을 한 번 읽는다(5절).

## 9. C-5에서 정한 것 (2026-10-17 메인)

- 검산 값은 기준대로 내린 값이다(7.27, 12.83. 앞 판의 7.28, 12.84는 반올림이었다).
- 강조색 `""`(빈 글)은 `INVALID_FORMAT`이다(`null`만 기본).
- "보낸 값이 지금과 같음"(S9-AC-07)은 정리한 뒤의 값으로 견준다: 이름은 앞뒤 공백을 자르고 NFC로 맞춘 것, 강조색은 대문자로 맞춘 것.
- S9-AC-05의 첫 관리자 전 503은 있는 동작(`SetupRequiredFilter`)이라 새 테스트를 쓰지 않는다.
- 다시 인코딩한 로고 PNG의 청크는 IHDR, PLTE, tRNS, IDAT, IEND만이다(sRGB, gAMA, pHYs, 텍스트 청크도 쓰지 않는다). 같은 파일을 다시 올리면 같은 PNG가 나온다.
- 테마 경로 값은 대소문자를 가린다(`LIGHT`는 404).
- 동시 디코딩 503과 `MAIL_FROM`에 이름이 있는 설정은 잠그는 테스트가 없다(붙잡아 둘 길이 없음, 컨텍스트가 하나 더 듦). C-10에서 메인이 코드로 본다.

## 10. C-7 점검에서 정한 것 (2026-10-17 메인)

`tasks/s9-c7-server-report.md`, `s9-c7-web-report.md`. 함께 만족할 수 없는 단언 쌍은 없었다.

- 웹 로고 상태: 브라우저는 테마마다 "자기 로고"를 따로 든다(공개 주소의 경로에 든 테마로 가린다: `/logos/light`는 밝은 테마 것). 보이는 짝은 "자기 것, 없으면 다른 테마 것"이다. 그래서 밝은 로고만 있다가 지우면 둘 다 빈다. `setLogo(theme, url | null)`은 그 테마의 자기 로고만 바꾼다.
- C-14를 열 때 미리 보기 API를 부르지 않는다(관리자 응답에 색과 대비가 있다). 늦은 미리 보기 요청은 끊지 않고(`abort` 없음) 차례 번호로 버린다.
- 브랜딩이 기본(`colors`가 `null`)으로 바뀌면 `documentElement`의 `--brand-*` 여덟을 지운다. 앱은 `--brand-*` 여덟 말고는 `documentElement`에 넣지 않는다.
- 저장소: 키 하나(`popdesk.branding`)에 공개 응답 전체를 JSON으로 둔다. 읽을 때 `parseBranding`을 지나야 쓴다. 첫 그림 전에(`useLayoutEffect`) 그 값으로 칠한다.
- C-14를 떠난 뒤 온 저장·로고 답은 앱 전체 브랜딩에 반영한다(C-6 사진의 "떠났으면 버림" 꼴을 베끼지 않는다).
- 웹의 "바뀐 것 없음"도 정리한 뒤의 값으로 견준다(이름 앞뒤 공백·NFC, 강조색 대문자).
- 로고 올리기에 `file` 칸이 없으면 프레임워크가 다른 검사(요청 수, 테마)보다 먼저 400 `file` `REQUIRED`를 낸다. 계약 설명을 그렇게 고친다(C-9 서버 탭).
- 보강 탭 둘(`s9-tfix-server`, `s9-tfix-web`)이 빈틈을 메운다(지시서).

