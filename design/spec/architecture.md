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

- Postgres 하나, 스키마 하나(앱 DB의 `public`), Flyway 이력 하나다. 앱이 스키마를 따로 만들지 않으므로 DB 사용자에게 `public`의 `CREATE`가 없으면 뜨지 않는다(첫 묶음 S0-AC-10). 테이블마다 맡는 모듈을 `server/README`의 표에 적는다.
- 팀: 모든 업무 테이블에 `tenant_id bigint NOT NULL`이 있고 값은 늘 1이다. 첫 실행 때 팀 1을 만든다. 조회·쓰기는 팀으로 거르지 않는다(여러 팀을 열 때 더한다, D-18).
  - 업무 테이블: 아래 예외 목록에 이름이 있는 테이블 말고는 모두 업무 테이블이다. 감사 로그, 보내기 대기, 정리 작업 기록, 운영 경보도 업무 테이블이다.
  - 예외는 이름을 하나씩 적고 이유를 붙인다. 이름 패턴(접두어, 정규식)으로 예외를 만들지 않는다.

    | 테이블 | 이유 |
    |---|---|
    | `flyway_schema_history` | Flyway가 만들고 모양을 정한다. 설치 하나의 스키마 이력이라 팀과 상관없다 |
    | `spring_session` | Spring Session JDBC가 모양을 정한다. 세션은 계정을 가리키고, 계정 테이블에 `tenant_id`가 있다 |
    | `spring_session_attributes` | `spring_session`과 같다 |
    | `tenant` | 팀 자체를 담는 테이블이다 |

  - 관문은 둘이다. CI가 마이그레이션 글을 읽어 검사하고(`scripts/ci/check-tenant-id.py`), 테스트가 마이그레이션을 다 돌린 실제 스키마의 `information_schema.columns`를 검사한다. 글 검사는 `CREATE TABLE … (LIKE x)`, 파티션 자식, 함수 안에서 만든 테이블을 놓칠 수 있어서 둘 다 둔다(첫 묶음 S0-AC-103).
- 마이그레이션은 앞으로만 간다. 이미 나간 마이그레이션 파일은 고치지 않고, 되돌리는 마이그레이션(down)도 두지 않는다. 데이터를 지우거나 되돌릴 수 없게 바꾸는 마이그레이션은 릴리스 노트에 표시한다(D-24).
- 시각은 `timestamptz`(UTC)로 저장한다. API는 오프셋이 붙은 ISO-8601로 준다. 날짜만 있는 값(마감일)은 설치 시간대(`POPDESK_TIMEZONE`, 기본 `UTC`)로 오늘을 판정한다.
- 첨부 파일 내용은 저장소 인터페이스 하나로 읽고 쓴다. 구현은 셋이고 `POPDESK_STORAGE`로 고른다: `db`(기본, `bytea`), `disk`(볼륨의 폴더), `s3`(S3 호환 저장소). 첨부 행마다 어느 저장소의 어디(버킷·폴더)에 있는지 적어 두어, 설정을 바꿔도 이전 파일은 원래 자리에서 읽는다. 파일을 쓰기 전에 `pending` 행을 먼저 커밋하고, 지울 때는 행에 표시를 남겨 커밋한 뒤 파일을 지운다(ATT-10·11). 상한은 파일 10MB, 문의 하나의 공개 첨부 50MB(내부 댓글 첨부는 세지 않는다), 팀 전체 `POPDESK_ATTACHMENT_QUOTA`다(D-38, ATT-2).

### 첨부 저장

384MB 힙에서 10MB 첨부를 동시에 올리고 내려받으려면 요청 하나가 파일을 메모리에 여러 벌 들면 안 된다. 단순하게 짜면(`MultipartFile.getBytes()` → 엔티티 `byte[]` → JPA) 요청 하나가 약 30MB를 잡는다. pgjdbc는 한 연결에서 같은 SQL의 처음 5번을 hex 글자로 받아 3배를 쓰고, Hibernate는 `byte[]`를 한 벌 더 복사한다. pgjdbc로는 `bytea` 값 하나를 흘려 읽을 수 없다(첫 묶음 사전 부검 1절에서 직접 쟀다).

- `db` 저장소의 파일 내용은 메타데이터와 다른 테이블(`attachment_content`)에 둔다. JPA 엔티티에는 `byte[]`·`@Lob` 칸을 두지 않는다(ArchUnit로 막는다).
- 쓰기는 `setBinaryStream(in, 길이)`로 흘려 쓴다. 첨부 쓰기 코드는 `setBytes`를 부르지 않는다.
- 읽기는 `substring(data from ? for ?)`로 1MB 조각씩 읽어 응답에 바로 쓴다.
- 올리기와 내려받기는 각각 동시에 16건까지다. 넘으면 503과 `Retry-After`로 답한다.
- multipart 상한은 10MB보다 조금 크게 두고, `file-size-threshold`는 0이다(받은 파일을 메모리에 두지 않고 바로 임시 파일로).
- 저장소에서 파일 내용을 읽는 것은 범위가 걸린 첨부 서비스 한 곳뿐이다. 다른 서비스가 저장소를 바로 부르지 않는다(ArchUnit). 첨부 조회는 문의를 볼 수 있는지와 내부 댓글 조건을 같은 쿼리에 붙인다(`docs/integrate/conventions.md` 첨부 경로).
- 앱이 평소 쥐는 힙은 아직 모른다. 첫 뼈대를 띄워 재고 7절을 고친다. 0.5GB 컨테이너에서는 JVM이 Serial GC를 고를 수 있으므로 부하 테스트는 운영과 같은 GC 옵션으로 돈다.
- 검색: 제목과 내용의 화면 글자 칸에 `pg_trgm` GIN 색인을 두고 `ILIKE`로 찾는다(D-13).
- 문의 번호는 고객사 행의 순번 칸을 같은 트랜잭션의 끝에서 `UPDATE … RETURNING`으로 올려 만든다. 번호가 비지 않고, 고객사 행 잠금은 커밋 직전에만 잡힌다. 번호가 겹치지 않는 것은 이 행 잠금이 지키고, 문의 테이블의 (고객사, 번호) 유니크 제약이 마지막 방어선이다. 고객사 행의 순번 카운터 칸에는 유니크 색인을 두지 않는다(아래 동시성 절, ISSUE-3).
- 문의 아래의 쓰기와 시스템 작업은 문의 행을 잠그거나 조건부 UPDATE로 한다(STATUS-7). 사람이 고치는 요청은 문의 `version`으로 앞 사람의 변경을 덮지 않는다(ISSUE-6).

### 동시성과 잠금 순서

잠금 순서가 엇갈리면 같은 고객사의 동시 등록이 교착(40P01)으로 실패한다. 외래 키가 있는 INSERT는 부모 행에 `FOR KEY SHARE`를 걸고, 이것은 `FOR UPDATE`와만 부딪힌다. 그래서 교착은 세 경우에 났다: 순번이나 문의 행을 `SELECT … FOR UPDATE`로 잠글 때, 자식 행을 넣은 뒤 부모를 `FOR UPDATE`로 잠글 때, 순번 칸에 유니크 색인이 걸릴 때(첫 묶음 사전 부검 2절에서 직접 재현했다).

한 트랜잭션은 이 순서로 잠근다.

1. 이미 있는 문의 행을 `FOR NO KEY UPDATE`로 잠근다. 어떤 INSERT보다 먼저 한다.
2. 첨부 행을 조건부 UPDATE로 바꾼다(`pending` → `stored` 등). 여러 행이면 ID 오름차순이다.
3. 새 행을 INSERT한다: 댓글, 보내기 대기, 감사.
4. 고객사 순번을 `UPDATE … RETURNING`으로 올린다(문의 등록만).
5. (1번 묶음부터) 감사 사슬 머리 행을 고친다.

- `NO KEY`가 없는 `FOR UPDATE`는 쓰지 않는다.
- 새 문의의 ID는 앱에서 먼저 정한다. 첨부의 문의 외래 키를 `DEFERRABLE INITIALLY DEFERRED`로 두어 2에서 첨부가 아직 없는 문의 ID를 가리킬 수 있게 하고, 문의 행은 4에서 번호를 받은 뒤 넣는다. 그래서 문의 번호 칸에 NOT NULL을 건다.
- 첨부의 ①(`pending` 커밋)과 ②(저장소 쓰기)는 업무 트랜잭션을 열기 전에 끝낸다. 업무 트랜잭션 안에서 `REQUIRES_NEW`로 부르지 않는다.
- DB 연결에 `lock_timeout` 5초를 건다. 40P01(교착)이나 55P03(잠금 시간 초과)이면 트랜잭션 전체를 한 번만 다시 시도한다(`docs/integrate/conventions.md`).
- 팀 총량 한도(ATT-3)는 잠그지 않고 센다. 동시 업로드로 한도를 조금 넘는 것은 받아들인다.

### 보내기 대기 (outbox)

메일과 웹훅은 DB 트랜잭션 밖의 일이라, 요청이 성공했는데 메일만 빠지거나 그 반대가 되지 않게 한다.

1. 요청이 바꾼 내용과 보내기 대기 행을 같은 트랜잭션에 저장한다. 메일 행에는 받는 계정 ID만 두고 주소는 보낼 때 읽는다(MAIL-5).
2. 보내기 루프(채널마다 스레드 하나)가 오래된 행부터 `FOR UPDATE SKIP LOCKED`로 잡아 트랜잭션 밖에서 보낸다. 커밋 직후 깨우고, 깨우지 않아도 1초마다 돈다.
3. 성공하면 행을 지운다. 실패하면 시도 횟수와 다음 시도 시각을 남긴다. 다시 보내는 간격, 포기하는 시점, 중복을 받아들이는 범위는 D-39에 있다.
4. 대기 행 수와 실패 수를 지표로 낸다(6절).

### 메일 받기

- 설정이 있으면 1분마다 IMAP 메일함(`IMAP_FOLDER`, 기본 `INBOX`)의 메일을 읽는다. `Message-ID`(없으면 원문 해시)는 문의·댓글을 만드는 트랜잭션에 함께 저장하고 유니크 제약으로 두 번 처리하지 않는다. 커밋한 뒤에 메일을 처리됨 폴더로 옮긴다. 다섯 번 실패한 메일은 격리 폴더로 옮긴다(MAILIN-6·8).
- 보내는 알림 메일마다 `Message-ID`를 만들어 저장한다. 답장의 `In-Reply-To`·`References`가 그 값을 가리키면 그 문의의 댓글로 잇는다. 머리글이 없으면 제목의 `[문의 키]`로 찾는다.
- 규칙은 `features.md` 11절(MAILIN)에 있다.

## 5. 로그인과 세션

- 서버 세션(Spring Session JDBC)과 `HttpOnly` 쿠키를 쓴다. 토큰을 브라우저 저장소에 두지 않는다(D-9).
- 쿠키는 호스트 전용(Domain 속성 없음)이라 포털과 콘솔의 로그인이 섞이지 않는다. 고객 계정은 콘솔에, 상담원·관리자 계정은 포털에 로그인할 수 없다.
- 세션은 로그인하고 8시간 뒤에 끝난다. 정지, 비밀번호 변경, 역할 변경 때는 그 계정의 세션을 모두 지운다.
- 세션 지우기는 한 곳(`Sessions.endAll`)에서 바꾼 내용과 같은 트랜잭션으로 한다. 요청마다 계정의 상태와 역할을 DB에서 다시 읽고, 비밀번호 바꾼 시각(`account.password_changed_at`)보다 먼저 로그인한 세션은 세지 않는다. 지우기와 겹쳐 처리 중이던 로그인의 세션이 남아도 막힌다(1a).
- 비밀번호 재설정 토큰은 초대 토큰과 다른 표(`password_reset`)에 두어 서로 쓰이지 않는다. 링크는 초대와 같이 설정한 공개 주소로만 만든다. 관리자의 초기화는 해시를 null로 두지 않고 어떤 비밀번호와도 맞지 않는 표지 값으로 바꾼다(수락했는지를 `password_hash`가 있는지로 보기 때문이다)(1a).
- 마지막 관리자: 정지와 역할 변경은 같은 메서드를 거치고, 관리자 행들을 ID 순서로 `FOR NO KEY UPDATE`로 잠근 뒤 `ACTIVE` 관리자 수를 센다(1a).
- 프로필 사진(D-52, D-53): 2MB 확인 → 푸는 자리 둘(넘으면 503) → 앞머리 바이트로 형식 → 머리글의 픽셀 수(PNG·JPEG 2,500만, WebP 1,200만) → 줄여 읽기 → 긴 변 256px → PNG로 다시 쓰기 → `account_photo`. 응답은 `image/png`, `private, no-cache`, `ETag`이고 권한 검사는 304보다 먼저다(1a).
- 쓰기 요청은 CSRF 토큰을 확인한다(Spring Security 기본, 쿠키에 담아 화면이 머리글로 돌려준다).
- 로컬에서는 `portal.localhost`와 `console.localhost`를 쓴다. 브라우저가 `*.localhost`를 내 PC로 보내므로 따로 설정할 것이 없다.
- OIDC(선택): 설정이 있으면 로그인 화면에 OIDC 로그인 버튼이 생긴다. Spring Security OAuth2 Client로 붙인다. 계정을 새로 만들지 않고, 이미 있는 계정에 이메일로 맞춘다(AUTH-6, D-15).

## 6. 운영

### 설정

→ `docs/operate/configuration.md`로 옮김(환경변수 표, 공개 계약 규칙, 비밀값 규칙, compose 계약 제안, TLS).

### 관측

- 기본: `/actuator/health`(compose 상태 확인), `/actuator/prometheus`(지표). 로그는 JSON 한 줄씩 표준 출력으로 낸다(아래 로그 절).
- 선택: `docker compose -f deploy/compose.yml -f deploy/compose.obs.yml up`으로 Grafana LGTM 이미지 하나를 더 띄운다(Grafana, Prometheus, Loki, Tempo가 한 컨테이너). 대시보드 JSON은 `deploy/grafana/`에 둔다.
- 지표: 요청 수와 지연, 보내기 대기 행 수와 실패 수, 정기 작업 처리 수, DB 크기, 첨부 총량.

### 로그

- JSON 한 줄씩 표준 출력으로 낸다. 이메일은 첫 글자 뒤를 가리고, 비밀번호, 토큰, 초대 링크, 문의·댓글 내용, 웹훅 주소, 비밀값은 남기지 않는다(PII-2).
- DB 오류 메시지에 값이 섞여 나오지 않게 한다. pgjdbc는 기본값(`logServerErrorDetail=true`)에서 서버 오류의 DETAIL(`Key (email)=(…) already exists.`)을 예외 메시지에 넣고, Hibernate `SqlExceptionHelper`가 그것을 WARN으로 남긴다. 그래서:
  - JDBC URL 기본값에 `logServerErrorDetail=false`를 넣는다(`docs/operate/configuration.md`).
  - 오류 처리기는 DB 예외를 제약 이름으로 오류 `code`에 옮기고, 예외의 `getMessage()`를 응답과 로그에 쓰지 않는다.
  - `SqlExceptionHelper` 로거의 WARN을 끈다.
  - 로그 출력 단계에 이메일 모양을 가리는 필터를 마지막 방어선으로 둔다. 스택 추적도 거친다.
- 로그 검사 테스트는 정상 흐름뿐 아니라 409·500 경로도 지나고, 원문, 퍼센트 인코딩, JSON 이스케이프 세 형태를 찾는다(첫 묶음 S0-AC-86).

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
- RTO(복구에 드는 시간): 견본 데이터(문의 10만 건, 첨부 5GB)로 복구 시간을 재서 문서에 적는다. v0.1부터 재고, 릴리스마다 다시 잰다.
- 여러 인스턴스와 DB 이중화는 지원하지 않는다(D-23).

## 7. 규모와 자원

- 목표 규모(설치 하나): 상담원·관리자 50명, 고객사 100곳, 고객 3,000명, 문의 하루 1,000건, 피크 100 RPS 미만.
- 앱 인스턴스는 하나다(D-23). 요청 수 제한, 보내기 루프 깨우기, 메일 받기 회차는 인스턴스 하나를 전제로 짠다. 정기 작업과 보내기 대기는 조건부 UPDATE와 `SKIP LOCKED`라서 둘이 떠도 데이터가 깨지지는 않는다. 예외는 감사 로그 사슬(AUDIT-6)과 SLA 다시 계산(SLA-4)이다. 머리 행 잠금과 계산 버전 조건으로 막지만, 둘이 함께 도는 것을 전제로 시험하지 않았다. 둘 이상 띄우는 것은 지원하지 않고, 아래 잠금으로 막는다. 여러 인스턴스와 캐시는 근거가 생길 때 정한다.
- 인스턴스 하나 지키기: 앱이 뜰 때 풀과 따로 연 전용 DB 연결로 advisory lock을 잡고, 앱이 끝날 때까지 그 연결을 쥐고 있는다(풀 연결로 잡으면 연결이 바뀔 때 잠금이 조용히 풀린다). 못 잡으면 정해진 시간 동안 다시 시도한다(막 죽은 앱의 옛 연결이 DB에 남아 있는 경우를 넘기려고). 그래도 못 잡으면 다른 인스턴스가 떠 있다고 남기고 멈춘다. 전용 연결이 끊기면 잠금도 풀렸으므로 앱은 정기 작업과 보내기 루프를 멈추고 다시 잡기를 시도한다. 잠금을 잡는 때와 방법은 아래 기동 순서에 있다.

### 기동 순서

Flyway 12.4는 기본으로 마이그레이션마다 트랜잭션 advisory lock을 잡고, 마이그레이션 SQL은 다른 연결에서 돈다. `CREATE INDEX CONCURRENTLY`는 잠금을 쥔 연결의 트랜잭션이 끝나길 기다리고, 그 연결은 `CONCURRENTLY`가 끝나길 기다린다. DB는 이것을 교착으로 보지 않아 업그레이드가 멈춘다. 앱 잠금 없이 두 대가 겹쳐 뜨면 둘 다 "관리자 없음"을 읽고 첫 관리자를 넣으려 한다(첫 묶음 사전 부검 3절에서 직접 재현했다).

1. 앱 단일 인스턴스 잠금. `FlywayMigrationStrategy` 안에서, 풀 밖 전용 연결(autocommit)로 두 int 키의 advisory lock을 잡는다. Flyway의 bigint 키와 겹치지 않는다. 못 잡으면 Flyway를 돌리기 전에 멈춘다.
2. Flyway 마이그레이션. `spring.flyway.postgresql.transactional-lock=false`로 돌린다. Flyway는 Spring Boot가 관리하는 버전(12.4)을 쓴다. 실패로 남은 마이그레이션이 있으면 뜨지 않고 실패 버전과 복구 절차를 남긴다.
3. 첫 관리자. 마이그레이션 뒤 한 트랜잭션에서 "관리자가 없으면 만들기"로 한다. 값이 없거나 실패하면 예외로 끝내지 않고 readiness를 DOWN으로 둔다(ACCT-5).
4. HTTP 포트를 연다. Spring Boot는 Flyway가 도는 동안 포트를 열지 않으므로, 마이그레이션 중에는 상태 확인이 연결을 거부한다. compose는 `start_period`로, 쿠버네티스는 `startupProbe`로 기다린다(`docs/operate/configuration.md`).

- 컨테이너 안 복원 명령(`popdesk restore`)은 빈 DB이고 앱이 요청을 받기 전일 때만 돈다(D-46).
- 앱 잠금을 못 잡으면 `POPDESK_INSTANCE_LOCK_WAIT`(기본 `PT30S`) 동안 기다린 뒤 멈춘다. 뜬 뒤 잠금 연결이 끊기면 1초마다 다시 잡고, 그동안 보내기 루프와 정기 작업은 쉰다(첫 묶음 S0-AC-11). 남은 것: TCP keepalive 값. 호스트가 갑자기 꺼지면 DB가 세션 잠금을 늦게 푼다.
- 메모리(첫 묶음에서 `docker stats`로 잼): 앱 이미지 압축 193MB. 앱은 힙 384MB(G1, `MaxRAM=512m`, `MaxRAMPercentage=75`)이고 컨테이너 최고 약 595MiB(10MB 첨부 20건 동시 전송 중), Postgres 최고 약 334MiB. 힙은 컨테이너 한도를 따라 줄지 않는다. 개발할 때 Vite 둘 0.5GB(추정). 개발 PC(16GB)에서 테스트를 순차로 돌릴 수 있어야 한다. 릴리스마다 다시 잰다.

## 8. 개발과 CI

- 테스트: 서버는 JUnit과 Testcontainers(Postgres, 그리고 그 기능의 테스트에서만 MinIO·Keycloak·GreenMail), 포털은 Vitest와 Playwright. 이 컨테이너들은 테스트에서만 뜬다. 로컬에서는 바뀐 곳만 순차로 돌린다. CI도 바뀐 영역의 잡만 돌리고, 전체는 릴리스 태그와 손으로 돌릴 때만 돈다(D-48).
- API 계약: 코드에서 만든다. springdoc이 앱별 그룹으로 OpenAPI 문서 둘을 만들고, 그 문서로 `web/packages/api`의 TypeScript 클라이언트 둘(portal, console)을 만든다. 만든 OpenAPI 파일은 `docs/integrate/openapi-portal.json`과 `openapi-console.json`으로 커밋하고(D-43), 코드와 파일이 다르면 CI가 실패시킨다. "계약 먼저"는 컨트롤러 껍데기(경로·요청·응답 타입만)를 먼저 커밋하는 것으로 지킨다(D-21).
- CI 관문: 빌드, 테스트, 모듈 경계 검사, 아키텍처 규칙(업무 테이블의 `tenant_id NOT NULL`), OpenAPI 차이, 비밀값(gitleaks), 의존성 취약점(osv-scanner), 의존성 라이선스, SBOM 생성(CycloneDX, 서버·웹·이미지, 릴리스에 붙인다, D-29), 이미지 빌드. 시각 회귀는 Docker로 고정한 렌더 환경에서만 찍는다. 진행 절차는 `../process.md`에 있다.
- 시각은 주입한 `Clock`으로 읽는다. 테스트는 고정 시각을 쓴다.
