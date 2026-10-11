# 2번 묶음 작업 목록 (C-8)

> 한 세션 크기의 작업이다. 작업 하나는 "이 테스트들을 통과시킨다"로 적는다. 묶음이 끝나면 이 파일은 지운다.
> 서버 구현 탭은 하나다(서버 쪽이 1b보다 작다). 웹 탭은 계약만 있으면 되므로 서버 탭과 같은 때 돈다. 탭이 합쳐지면 메인이 `pending.txt`에서 그 탭의 줄을 지운다.
> 푸시는 둘을 다 합치고 이 PC에서 서버 전체가 초록인 뒤 한 번 한다(`process.md`의 CI 규칙).

## 1. 서버

| 탭 | 맡는 것 | 통과시킬 테스트 |
|---|---|---|
| `s2-i-server` | 마이그레이션 V8(`company_member`의 `manager`와 `row_id`, 표 `membership_request`와 색인, 알림 종류). 담당자 지정과 해제. 구성원 엔드포인트 넷과 권한 확인. 초대의 두 갈래, 한도, 횟수 제한. 빼기와 다시 보내기. 소속 요청 목록, 승인, 거절, 콘솔의 소속 더하기가 요청을 닫는 것. 알림 항목. 감사 항목. 계약 껍데기의 고정값(`manager`, `pendingMembershipRequests`, 알림의 `companyCode`)을 진짜 값으로. 업그레이드 견본 생성기. 문서 | `manager` 패키지의 모든 클래스, `project.NoStoreTest`, `deploy.ManagerUpgradeTest`. `pending.txt`의 `server` 줄 전부다 |

- V8은 SQL 하나다. 실험 결과(`s2-c4-server-report.md` "실험 1"): PostgreSQL 16.15에서 `ADD COLUMN row_id uuid NOT NULL DEFAULT gen_random_uuid()`는 확장 없이, 슈퍼유저가 아닌 사용자로, 있던 행마다 다른 값을 넣는다.
- 알림을 넣는 길: 지금 콘솔 알림은 걸렸다 풀리는 운영 경보(`OpsAlerts.Kind`)로만 들어간다. `MEMBERSHIP_REQUESTED`는 요청마다 하나씩 생기고 풀리지 않는다. 알림 표에 고객사 코드를 둘 칸과 종류 제약이 있는지 V3을 보고 V8에서 맞춘다.
- 문서: `docs/use/permissions.md`의 담당자 절을 `acceptance.md` 5절 1대로 고친다(취소를 빼기에 합침, 한도 100, 이름의 제어 문자). `docs/integrate/conventions.md`에 구성원 목록의 행 ID와 "두 경우의 답이 같다"를 적는다. `docs/operate/upgrade.md`에 V8을 적는다(되돌릴 수 없다, 오래 걸리지 않는다). `deploy/upgrade/README.md`의 견본 설명.
- deploy 태그 테스트는 탭이 이미지를 한 번 빌드해 `ManagerUpgradeTest`와 `UpgradeHarnessTest`만 돈다.

## 2. 웹

| 탭 | 맡는 것 | 통과시킬 테스트 파일 |
|---|---|---|
| `s2-i-web` | 공용 `AppShell`: `NavItem`의 `count`와 `countLabel` 그리기, 포털에서 메뉴의 자리와 좁은 화면의 둘째 줄. 포털 메뉴와 P-8 우리 회사 사람(고객사 고르기, 표, 줄 메뉴, 초대 대화상자, 늦게 온 답, 403). P-6의 담당자 배지. 콘솔 C-9의 담당자 배지와 지정·해제, 소속 요청 구역. C-8의 소속 요청 칸. 옆 메뉴의 합. C-12의 새 종류 여섯의 말 | `pending.txt`의 `web` 줄 전부다: `ui`의 `AppShell.navCount`, `portal`의 `P6MyInfo.manager`, `P8Members`, `P8Members.actions`, `P8Members.late`, `console`의 `layout.navCount`, `C8CompanyList.requests`, `C9CompanyDetail.manager`, `C9CompanyDetail.requests`, `C12AuditLog.kinds2`, 그리고 종류가 29로 늘어 고친 1b의 셋(`auditWords.words`, `C12AuditLog.filters`, `C12AuditLog`) |

브라우저에서 눌러 보는 것은 서버가 합쳐진 뒤 메인이 C-10에서 한다(1280px과 820px, 포털은 360px도, 밝게와 어둡게).

## 3. 구현이 받쳐야 하는 가정

테스트가 기준을 어떻게 읽었는지는 테스트 코드가 가장 정확하다. 탭 보고의 가정 절에 모아 두었다.

| 탭 | 읽을 보고(`C:\Users\minju\.claude\popdesk\tasks\`) |
|---|---|
| 서버 | `s2-t-members-report.md`의 "정한 것"과 "운영 코드가 받쳐 줘야 하는 가정", `s2-t-requests-report.md`의 같은 두 절, `s2-tfix-server-report.md`의 "고정한 감사 항목의 꼴"과 새 가정, `s2-c4-server-report.md`의 "있는 계약에 더한 칸과 고정값"과 질문 4 |
| 웹 | `s2-t-web-report.md`의 "운영 코드가 받쳐 줘야 하는 가정", `s2-tfix-web-report.md`의 "테스트가 구현에 바라게 된 것", `s2-c7-web-report.md`의 "구현하면 깨질 잠긴 테스트" 표와 그 아래 "구현 쪽에 넘길 것", `s2-c4-web-report.md`의 "포털에서 nav가 그려지는 모양" |

계약과 설계에서 정한 것은 `design.md` 0~6절과 `screens.md`, 오류 code는 `docs/integrate/conventions.md`의 표에 있다.
