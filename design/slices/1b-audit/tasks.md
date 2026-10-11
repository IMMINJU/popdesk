# 1b 묶음 작업 목록 (C-8)

> 한 세션 크기의 작업이다. 작업 하나는 "이 테스트 클래스들을 통과시킨다"로 적는다. 묶음이 끝나면 이 파일은 지운다.
> 서버 구현 탭은 한 번에 하나만 돈다. 웹 탭은 계약만 있으면 되므로 서버 1번과 같은 때 돈다. 탭이 합쳐지면 메인이 `pending.txt`에서 그 탭의 줄을 지운다.
> 푸시는 셋을 다 합친 뒤 한 번 한다(`process.md`의 CI 규칙).

## 1. 서버

| 차례 | 탭 | 맡는 것 | 통과시킬 테스트 클래스 |
|---|---|---|---|
| 1 | `s1b-i-chain` | 마이그레이션 V6(칸과 표, 색인, `outbox`·`delivery_status`의 채널 값과 `audit_id`)와 V7(소급 계산). 종류 enum을 서비스가 쓰게 하기. 사슬에 붙이기(커밋 직전, 앱과 컨테이너 안 명령이 같은 코드). 보기, 내보내기(자리 둘, `AUDIT_EXPORTED`), 무결성 확인. 업그레이드 견본 생성기. 문서: `conventions.md`의 해시 규칙과 `audit-verify.py` | `audit`의 `AuditLogViewTest`, `AuditChangesTest`, `AuditLogAccessTest`, `AuditExportTest`, `AuditExportLimitTest`, `AuditChainTest`, `AuditTamperTest`(보관 작업이 필요한 `firstEntryLeftAfterRetentionIsChecked` 둘은 2번 탭 뒤에 통과한다). deploy 태그 `UpgradeAuditChainTest`, `UpgradeInterruptedTest`, `UpgradeHarnessTest` |
| 2 | `s1b-i-ops` | 보관 기간(설정 검사, 하루 한 번 지우기, 시작점). 바깥 사본(대기 행, 보내기, 토큰, 샌드박스, 시스템 상태, `failed` 목록). 컨테이너 안 명령의 사본 대기. 설정 키와 `deploy/compose.yml`. 문서: 설정, 사본의 본문과 중복·순서, 무결성 확인의 한계, 업그레이드에 걸리는 시간 | `audit`의 `AuditRetentionTest`, `AuditForwardTest`, `AuditForwardOffTest`, `AuditForwardSandboxTest`, 1번이 남긴 `AuditTamperTest`의 둘. `start.AuditRetentionSettingTest`. deploy 태그 `CommandAuditChainTest` |

- 마이그레이션은 1번 탭이 V6과 V7로 모두 쓴다(2번 탭의 `outbox` 칸과 채널 값까지). 2번 탭에 칸이 더 필요하면 V8을 더하고 보고에 이유를 적는다.
- 사슬에 붙이는 코드는 1번 탭이 "사본이 켜져 있으면 대기 행도 넣는다"를 꽂을 자리를 두고, 넣는 것은 2번 탭이 한다.
- 계약 껍데기의 고정값(`auditForward` 꺼짐, `auditEntryId: null`)을 진짜 값으로 바꾸는 것은 2번 탭이 한다.
- deploy 태그 테스트는 탭이 이미지를 한 번 빌드해 맡은 클래스만 돈다(이미지 빌드 1분 30초, 클래스 하나에 30초~1분).

## 2. 웹

| 차례 | 탭 | 맡는 것 | 통과시킬 테스트 파일 |
|---|---|---|---|
| 1과 같이 | `s1b-i-web` | 공용 `DataTable` 줄 펼치기, `Select` 선택지 묶음. 옆 메뉴. C-12 감사 로그(표, 필터, 내보내기, 무결성 확인). C-18의 감사 바깥 사본 줄과 `failed` 목록 | `ui`의 `DataTable.expand`, `Select.group`. `console`의 `C12AuditLog`, `C12AuditLog.filters`, `C12AuditLog.actions`, `C18SystemStatus.1b`, `adminOnly.1b` |

브라우저에서 눌러 보는 것은 서버가 다 합쳐진 뒤 메인이 C-10에서 한다.

## 3. 구현이 받쳐야 하는 가정

테스트가 기준을 어떻게 읽었는지는 테스트 코드가 가장 정확하다. 탭 보고의 "운영 코드가 받쳐 줘야 하는 가정" 절에 모아 두었다.

| 탭 | 읽을 보고(`C:\Users\minju\.claude\popdesk\tasks\`) |
|---|---|
| 1 | `s1b-t-read-report.md` 5절, `s1b-t-chain-report.md` 5절(1~3, 5, 7, 9, 10), `s1b-tfix-report.md`의 "새 가정" 1~4, 6, 7 |
| 2 | `s1b-t-chain-report.md` 5절(4, 5, 6, 8), `s1b-t-forward-report.md` 5절, `s1b-tfix-report.md`의 "새 가정" 5 |
| 웹 | `s1b-t-web-report.md`의 가정 절, `s1b-tfix-report.md`의 "새 가정" 8 |

계약과 설계에서 정한 것은 `design.md` 0~3절, 오류 code는 `docs/integrate/conventions.md`의 표에 있다. 내보내기는 저장된 해시를 준다(다시 계산해 주지 않는다. 다시 계산하는 것은 무결성 확인뿐이다).
