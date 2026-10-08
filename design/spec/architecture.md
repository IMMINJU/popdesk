# popdesk 구조 (분해 대기 초안)

> 앱이 어떻게 짜이고 어떻게 뜨는지 적는다. 기능 규칙은 `features.md`, 이유는 `../adr.md`에 있다.
> 정본이 아니다(D-27). 묶음을 시작할 때 설정·규모·백업은 `docs/operate/`로, 공통 규약은 `docs/integrate/`로, 결정은 ADR로 옮기고 옮긴 절은 여기서 지운다.

## 1. 한눈에

```
브라우저 ─┬─ portal.<호스트>  ─┐
          └─ console.<호스트> ─┤
                              ▼
                 popdesk 앱 (Spring Boot, 컨테이너 하나)
                 ├─ API: /api/portal/** (portal 호스트만)
                 │       /api/console/** (console 호스트만)
                 │       /api/public/** (둘 다, 로그인 전)
                 ├─ 고객 포털 정적 파일 (portal 호스트)
                 ├─ 상담 콘솔 정적 파일 (console 호스트)
                 └─ 정기 작업 · 보내기 루프
                              │
                              ▼
                       PostgreSQL 16 (하나)
                              
   밖과 주고받는 것(모두 선택): SMTP(메일 보내기), IMAP(메일 받기),
   웹훅(팀 알림), OIDC 제공자(로그인)
```

- 컨테이너는 기본으로 둘이다: `app`, `db`. 관측 도구는 따로 켠다(6절).
- 메시지 브로커(Kafka, RabbitMQ)는 두지 않는다. 밖으로 나가는 일은 DB의 보내기 대기 테이블로 처리한다(4절, D-5).

## 2. 레포 짜임

```
popdesk/
├─ server/                 Spring Boot 앱 (Java 25, Maven)
├─ web/                    pnpm workspace
│  ├─ portal/              고객 포털 (React, Vite)
│  ├─ console/             상담 콘솔 (React, Vite)
│  └─ packages/
│     ├─ ui/               디자인 시스템 부품
│     ├─ api/              API 클라이언트와 타입
│     └─ i18n/             문구(ko, en)
├─ deploy/
│  ├─ compose.yml          app + db
│  └─ compose.obs.yml      관측 도구(선택)
├─ docs/
└─ LICENSE                 Apache-2.0
```

- 두 포털은 앱을 따로 두고 공통 코드는 `web/packages`에 둔다. 한 앱으로 합치지 않는다(D-7).
- 서버 이미지를 만들 때 두 포털을 빌드해서 앱 안에 넣는다. 배포물은 이미지 하나다.

## 3. 서버 모듈

Spring Modulith로 모듈 경계를 정하고, 경계를 넘는 호출은 테스트(`ApplicationModules.verify()`)로 막는다.

| 모듈 | 맡는 것 |
|---|---|
| `helpdesk` | 계정·초대·로그인, 고객사·소속·배정, 문의, 댓글, 멘션, 첨부, 라벨, 마감일, 작업, 이력, 휴지통, 알림함, 통계, 감사 로그, 개인정보 내보내기·익명화 |
| `notification` | 보내기 대기 테이블, 메일 보내기(SMTP), 웹훅, 다시 보내기, 메일 받기(IMAP) |

- `helpdesk`는 알릴 일이 생기면 같은 트랜잭션 안에서 `notification`의 공개 API로 보내기 대기 행을 남긴다.
- 메일 받기는 `notification`이 메일을 읽고 해석한 뒤 `helpdesk`의 공개 API(문의 등록, 댓글 달기)를 부른다. 권한과 규칙은 포털로 들어온 요청과 같다.
- 그 밖에 두 모듈은 서로의 테이블을 읽지 않는다.
- 통계는 문의 테이블을 바로 세므로 따로 모듈을 두지 않는다(D-6).
- 모듈을 더 나눠야 할 근거(규모, 배포 단위)가 생기면 결정을 먼저 남긴다.

## 4. 데이터

- Postgres 하나, 스키마 하나(`popdesk`), Flyway 이력 하나다. 테이블마다 맡는 모듈을 `server/README`의 표에 적는다.
- 팀: 모든 업무 테이블에 `tenant_id bigint NOT NULL`이 있고 값은 늘 1이다. 첫 실행 때 팀 1을 만든다. 조회·쓰기는 팀으로 거르지 않는다(여러 팀을 열 때 더한다, D-18). CI 관문이 마이그레이션을 읽어 업무 테이블에 이 칸이 빠지면 실패시킨다.
- 마이그레이션은 앞으로만 간다. 이미 나간 마이그레이션 파일은 고치지 않고, 되돌리는 마이그레이션(down)도 두지 않는다. 데이터를 지우거나 되돌릴 수 없게 바꾸는 마이그레이션은 릴리스 노트에 표시한다(D-24).
- 시각은 `timestamptz`(UTC)로 저장한다. API는 오프셋이 붙은 ISO-8601로 준다. 날짜만 있는 값(마감일)은 설치 시간대(`POPDESK_TIMEZONE`, 기본 `UTC`)로 오늘을 판정한다.
- 첨부 파일 내용은 저장소 인터페이스 하나로 읽고 쓴다. 구현은 셋이고 `POPDESK_STORAGE`로 고른다: `db`(기본, `bytea`), `disk`(볼륨의 폴더), `s3`(S3 호환 저장소). 첨부 행마다 어느 저장소에 있는지 적어 두어, 설정을 바꿔도 이전 파일은 원래 저장소에서 읽는다. 상한은 파일 10MB, 문의 하나 50MB, 팀 전체 `POPDESK_ATTACHMENT_QUOTA`다(D-38).
- 검색: 제목과 내용의 화면 글자 칸에 `pg_trgm` GIN 색인을 두고 `ILIKE`로 찾는다(D-13).
- 문의 번호는 고객사 행의 순번 칸을 같은 트랜잭션에서 올려 만든다. 번호가 비지 않는다.

### 보내기 대기 (outbox)

메일과 웹훅은 DB 트랜잭션 밖의 일이라, 요청이 성공했는데 메일만 빠지거나 그 반대가 되지 않게 한다.

1. 요청이 바꾼 내용과 보내기 대기 행을 같은 트랜잭션에 저장한다.
2. 보내기 루프(채널마다 스레드 하나)가 오래된 행부터 `FOR UPDATE SKIP LOCKED`로 잡아 트랜잭션 밖에서 보낸다. 커밋 직후 깨우고, 깨우지 않아도 1초마다 돈다.
3. 성공하면 행을 지운다. 실패하면 시도 횟수와 다음 시도 시각을 남긴다. 다시 보내는 간격, 포기하는 시점, 중복을 받아들이는 범위는 D-39에 있다.
4. 대기 행 수와 실패 수를 지표로 낸다(6절).

### 메일 받기

- 설정이 있으면 1분마다 IMAP 메일함(`IMAP_FOLDER`, 기본 `INBOX`)에서 읽지 않은 메일을 읽는다. 처리한 메일은 읽음으로 표시하고, `Message-ID`를 저장해 두 번 처리하지 않는다.
- 보내는 알림 메일마다 `Message-ID`를 만들어 저장한다. 답장의 `In-Reply-To`·`References`가 그 값을 가리키면 그 문의의 댓글로 잇는다. 머리글이 없으면 제목의 `[문의 키]`로 찾는다.
- 규칙은 `features.md` 11절(MAILIN)에 있다.

## 5. 로그인과 세션

- 서버 세션(Spring Session JDBC)과 `HttpOnly` 쿠키를 쓴다. 토큰을 브라우저 저장소에 두지 않는다(D-9).
- 쿠키는 호스트 전용(Domain 속성 없음)이라 포털과 콘솔의 로그인이 섞이지 않는다. 고객 계정은 콘솔에, 상담원·관리자 계정은 포털에 로그인할 수 없다.
- 세션은 로그인하고 8시간 뒤에 끝난다. 정지, 비밀번호 변경, 역할 변경 때는 그 계정의 세션을 모두 지운다.
- 쓰기 요청은 CSRF 토큰을 확인한다(Spring Security 기본, 쿠키에 담아 화면이 머리글로 돌려준다).
- 로컬에서는 `portal.localhost`와 `console.localhost`를 쓴다. 브라우저가 `*.localhost`를 내 PC로 보내므로 따로 설정할 것이 없다.
- OIDC(선택): 설정이 있으면 로그인 화면에 OIDC 로그인 버튼이 생긴다. Spring Security OAuth2 Client로 붙인다. 계정을 새로 만들지 않고, 이미 있는 계정에 이메일로 맞춘다(AUTH-6, D-15).

## 6. 운영

### 설정

→ `docs/operate/configuration.md`로 옮김(환경변수 표, 공개 계약 규칙, 비밀값 규칙, compose 계약 제안, TLS).

### 관측

- 기본: `/actuator/health`(compose 상태 확인), `/actuator/prometheus`(지표). 로그는 JSON 한 줄씩 표준 출력으로 낸다.
- 선택: `docker compose -f deploy/compose.yml -f deploy/compose.obs.yml up`으로 Grafana LGTM 이미지 하나를 더 띄운다(Grafana, Prometheus, Loki, Tempo가 한 컨테이너). 대시보드 JSON은 `deploy/grafana/`에 둔다.
- 지표: 요청 수와 지연, 보내기 대기 행 수와 실패 수, 정기 작업 처리 수, DB 크기, 첨부 총량.

### 백업

- 첨부 저장소가 `db`(기본)면 `pg_dump` 하나로 전부 백업된다. 복구는 `pg_restore` 뒤 앱을 띄운다.
- `disk`면 그 폴더를, `s3`면 그 버킷을 따로 백업한다. 같은 시점으로 맞추려면 DB를 먼저 뜨고 파일을 뒤에 뜬다(DB에 없는 파일은 남아도 되지만, DB가 가리키는 파일이 없으면 안 된다).
- 첨부가 늘면 저장 공간도 커진다. 첨부 총량 한도(`POPDESK_ATTACHMENT_QUOTA`)로 상한을 건다.
- 업그레이드 전에 백업한다. 릴리스 노트가 매번 이것을 적는다.

### 가용성 (D-33)

`docs/operate/`의 설치·백업 문서 첫머리에 아래를 그대로 밝힌다.

- 고가용성이 없다. 앱 하나, DB 하나다. 둘 중 하나가 멈추면 서비스가 멈춘다.
- 앱이 죽으면 compose의 `restart: unless-stopped`로 다시 뜬다. 보내기 대기와 메일 받기는 DB에 남아 있어 다시 뜬 뒤 이어서 처리한다.
- RPO(잃을 수 있는 데이터): 마지막 백업 뒤의 모든 변경. 백업 주기는 설치한 곳이 정한다. 문서는 하루 한 번 이상 `pg_dump`를 권하고, 더 줄이려면 Postgres WAL 보관(point-in-time recovery)을 쓰라고 안내한다. popdesk가 WAL 보관을 대신 설정하지는 않는다.
- RTO(복구에 드는 시간): 견본 데이터(문의 10만 건, 첨부 5GB)로 복구 시간을 재서 문서에 적는다. 재는 것은 6번 묶음이 끝난 뒤이고, 릴리스마다 다시 잰다.
- 여러 인스턴스와 DB 이중화는 지원하지 않는다(D-23).

## 7. 규모와 자원

- 목표 규모(설치 하나): 상담원·관리자 50명, 고객사 100곳, 고객 3,000명, 문의 하루 1,000건, 피크 100 RPS 미만.
- 앱 인스턴스는 하나다(D-23). 요청 수 제한, 보내기 루프 깨우기, 메일 받기 회차는 인스턴스 하나를 전제로 짠다. 정기 작업과 보내기 대기는 조건부 UPDATE와 `SKIP LOCKED`라서 둘이 떠도 데이터가 깨지지는 않지만, 둘 이상 띄우는 것은 지원하지 않는다. 여러 인스턴스와 캐시는 근거가 생길 때 정한다.
- 메모리(추정, 실측 전): 앱 0.5GB(`-Xmx384m`), Postgres 0.15GB, 개발할 때 Vite 둘 0.5GB. 개발 PC(16GB)에서 테스트를 순차로 돌릴 수 있어야 한다.
- 실측은 첫 뼈대가 뜨면 `docker stats`로 재서 이 절을 고친다.

## 8. 개발과 CI

- 테스트: 서버는 JUnit과 Testcontainers(Postgres, 그리고 그 기능의 테스트에서만 MinIO·Keycloak·GreenMail), 포털은 Vitest와 Playwright. 이 컨테이너들은 테스트에서만 뜬다. 로컬에서는 바뀐 곳만 순차로 돌리고 전체는 CI가 돈다.
- API 계약: 코드에서 만든다. springdoc이 앱별 그룹으로 OpenAPI 문서 둘을 만들고, 그 문서로 `web/packages/api`의 TypeScript 클라이언트 둘(portal, console)을 만든다. 만든 OpenAPI 파일은 `docs/integrate/openapi-portal.json`과 `openapi-console.json`으로 커밋하고(D-43), 코드와 파일이 다르면 CI가 실패시킨다. "계약 먼저"는 컨트롤러 껍데기(경로·요청·응답 타입만)를 먼저 커밋하는 것으로 지킨다(D-21).
- CI 관문: 빌드, 테스트, 모듈 경계 검사, 아키텍처 규칙(업무 테이블의 `tenant_id NOT NULL`), OpenAPI 차이, 비밀값(gitleaks), 의존성 취약점(osv-scanner), 의존성 라이선스, SBOM 생성(CycloneDX, 서버·웹·이미지, 릴리스에 붙인다, D-29), 이미지 빌드. 시각 회귀는 Docker로 고정한 렌더 환경에서만 찍는다. 진행 절차는 `../process.md`에 있다.
- 시각은 주입한 `Clock`으로 읽는다. 테스트는 고정 시각을 쓴다.
