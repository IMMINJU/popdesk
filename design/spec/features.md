# popdesk 기능 (분해 대기 초안)

> 기능마다 누가 무엇을 할 수 있고 어떤 값을 지키는지 적는다. 테스트는 이 문서의 규칙 ID를 이름에 적는다.
> 정본이 아니다(D-27). 묶음을 시작할 때 그 묶음의 절을 EARS 수용 기준(`../slices/`)으로 옮기고 여기서 지운다. 권한은 고객사 권한 모델(`docs/use/permissions.md`)로 옮긴다. 0절은 공통 규약으로 옮겼다.
> API 경로와 응답 모양은 묶음마다 컨트롤러 껍데기로 먼저 정하고, springdoc이 만든 `docs/integrate/openapi-portal.json`·`openapi-console.json`이 그 계약이다(D-21, D-43).
> 글자 수 세는 법, NFC 정규화, 앞뒤 공백 자르기는 `docs/integrate/conventions.md`에 있다.

## 옮긴 규칙

묶음을 시작하며 옮겨 간 규칙이다. 절 번호는 옮기기 전의 것이고, 다른 문서가 "features.md N절"이라고 적은 것은 이 표의 그 절이다. 규칙이 하나도 남지 않은 절은 제목을 지우고 이 표에만 남겼다.

| 절 | 규칙 ID | 옮긴 곳 |
|---|---|---|
| 0. 공통 | COM-1~8, 글자 수·NFC·공백 자르기, 시각, null의 뜻 제안 | `docs/integrate/conventions.md` |
| 1. 로그인과 계정 (AUTH, ACCT) | AUTH-1~5·7, ACCT-1~6·8 | `../archive/1a-people/rules.md` |
| 1. 로그인과 계정 (AUTH, ACCT) | ACCT-7 | `../archive/3-issues/rules.md`(3번 묶음) |
| 2. 초대 (INVITE) | INVITE | `../archive/1a-people/rules.md`. INVITE-5의 담당자·멘션 부분은 3번·4번 묶음에서, 고객사 담당자의 초대는 2번 묶음(MGR)에서 채운다 |
| 3. 고객사 (PROJ) | PROJ | `../archive/1a-people/rules.md` |
| 4. 문의 (ISSUE) | ISSUE-1~12, 상태 표, STATUS-1~4·6·7 | `../archive/3-issues/rules.md`(3번 묶음) |
| 4. 문의 (ISSUE) | STATUS-5 | `../archive/7a-trash/rules.md`(7a 묶음) |
| 5. 댓글과 멘션 (CMT, MNT) | CMT, MNT | `../archive/4-talk/rules.md`(4번 묶음) |
| 6. 첨부 (ATT) | ATT-6~8 | `../archive/5-attachments/rules.md`(5번 묶음) |
| 6. 첨부 (ATT) | ATT-9 | `../archive/5b-storage/rules.md`(5b 묶음) |
| 7. 라벨과 마감일 (LBL, DUE) | LBL, DUE | `../archive/6a-labels/rules.md`(6a 묶음) |
| 8. 작업 (TASK) | TASK | `../archive/6b-tasks/rules.md`(6b 묶음) |
| 8-1. 문의 연결 (LINK) | LINK | `../archive/6c-links/rules.md`(6c 묶음). LINK-2·7의 휴지통 몫은 7번 묶음이 그 파일에서 가져간다 |
| 9. 이력과 휴지통 (HIST, TRASH) | HIST-1~3 | `../archive/3-issues/rules.md`(3번 묶음) |
| 9. 이력과 휴지통 (HIST, TRASH) | TRASH-1~4 | `../archive/7a-trash/rules.md`(7a 묶음) |
| 10. 알림 (INBOX, MAIL, HOOK) | 알림 표, INBOX-1~4, MAIL-1~5, HOOK-1 | `../archive/4-talk/rules.md`(4번 묶음) |
| 10. 알림 (INBOX, MAIL, HOOK) | 알림 표의 SLA 줄 셋 | `../archive/8b-sla-alerts/rules.md`(8b 묶음) |
| 10. 알림 (INBOX, MAIL, HOOK) | MAIL-6 | `../archive/4b-delivery/rules.md` |
| 12. 통계 (STATS) | STATS-1~3 | `../archive/7b-stats/rules.md`(7b 묶음) |
| 12. 통계 (STATS) | STATS-4 | `../archive/8b-sla-alerts/rules.md`(8b 묶음) |
| 14. 감사 로그 (AUDIT) | AUDIT | `../archive/1b-audit/rules.md`. 익명화에 따른 사슬 재계산(AUDIT-6의 끝부분, AUDIT-7의 구간 나누기)은 10번 묶음에서 한다 |
| 15. 고객사 담당자 (MGR) | MGR | `../archive/2-manager/rules.md`(2번 묶음) |
| 16. SLA | SLA-1~4, 6, 8 | `../archive/8a-sla/rules.md`(8a 묶음) |
| 16. SLA | SLA-5, 7 | 8a와 8b가 나눠 가졌다. `../archive/8a-sla/rules.md`와 `../archive/8b-sla-alerts/rules.md` |
| 17. 브랜딩 (BRAND) | BRAND-1~5 | `../archive/9-branding/rules.md`(9번 묶음) |
| 13. 개인정보 (PII) | PII-3·4·5·7 | `../archive/10a-privacy/rules.md`(10a 묶음). PII-4의 OIDC 묶음 지우기는 AUTH-6, 받은 메일 기록의 주소 비우기는 MAILIN-6에 옮겨 적었다 |
| 13. 개인정보 (PII) | PII-1·2·6·8, 보관 기간 표 | `../archive/10b-privacy/rules.md`(10b 묶음) |
| 18. 운영 경보와 시스템 상태 (OPS) | OPS-3 | `../archive/4b-delivery/rules.md`(4b 묶음) |
| 1. 로그인과 계정 (AUTH) | AUTH-6 | `../archive/11-oidc/rules.md`(11번 묶음) |
| 10. 알림 (MAIL) | MAIL-7, MAIL-1의 `Reply-To` 몫 | `../slices/12-mailin/rules.md`(12번 묶음) |
| 11. 메일로 받기 (MAILIN) | MAILIN-1~9 | `../slices/12-mailin/rules.md`(12번 묶음) |

## 0. 공통

아래 규칙은 아직 공통 규약에 없다. 공통 규약으로 옮기면 여기서 지운다(TIME-1은 시각 절로).

| ID | 규칙 |
|---|---|
| TIME-1 | 기간(24시간, 7일, 14일, 30일, 보관 기간)은 UTC 시각의 차이로 잰다. 서머타임이나 `POPDESK_TIMEZONE` 변경과 관계없이 같은 길이다. 설치 시간대로 날짜를 판정하는 것은 마감일(DUE-1, TASK-6)과 SLA 업무 시간·쉬는 날(SLA-2)뿐이다 |
| ANON-1 | 고객 쪽(포털 화면, 포털 API, 고객에게 가는 메일·알림함)에는 상담 쪽 사람(상담원·관리자)을 "지원팀"으로만 보인다. 댓글 작성자, 첨부를 올리거나 지운 사람, 대리 등록한 사람(ISSUE-9), 고객에게 보이는 일을 일으킨 사람 모두 그렇다. 포털 API 응답에는 상담 쪽 사람의 이름, 이메일, 계정 ID를 넣지 않는다. 상담 쪽이 한 것은 "상담 쪽"이라는 표시만 담고, 화면과 메일이 그것을 "지원팀"으로 쓴다. 고객이 쓴 것의 작성자(올린 고객, 같은 고객사 동료)는 그대로 보인다(D-49) |

## 6. 첨부 (ATT)

| ID | 규칙 |
|---|---|
| ATT-1 | 문의나 댓글에 파일을 올린다. 올리는 사람은 그 문의에 댓글을 달 수 있는 사람이고, 댓글 첨부는 그 댓글을 쓴 사람만 붙인다. `CLOSED` 문의는 400이다 |
| ATT-2 | 파일 하나는 1바이트~10MB, 문의 하나의 공개 첨부(문의 첨부와 공개 댓글 첨부) 합은 50MB까지다. 내부 댓글 첨부는 이 합에 세지 않고 팀 총량 한도(ATT-3)에만 센다(고객이 400이 나는 지점으로 내부 첨부 크기를 짐작하지 못하게). 파일 이름은 1~255자다 |
| ATT-3 | 팀 전체 첨부 합이 `POPDESK_ATTACHMENT_QUOTA`(기본 10GB)를 넘게 되면 올리기가 400이다. 한도의 80%를 넘으면 경고 로그를 남기고 관리자 알림함에도 남긴다(OPS-1) |
| ATT-4 | 내려받기는 문의를 볼 수 있는 사람이 한다. 늘 `Content-Disposition: attachment`와 `X-Content-Type-Options: nosniff`로 내려준다. 고객에게 내부 댓글의 첨부는 없는 것과 같다(목록에서 빠지고, 내려받기는 404, CMT-2). 고객에게 상담 쪽이 올리거나 지운 첨부의 사람은 "지원팀"이다(ANON-1) |
| ATT-5 | 지우기는 올린 사람과 관리자가 하고 흔적을 남긴다(COM-7, 파일 내용은 지운다) |
| ATT-10 | 첨부 행의 파일 상태: `pending`(파일을 쓰는 중), `stored`, `purging`(파일을 지울 차례), `purged`. 올리기는 ① `pending` 행을 따로 커밋하고 ② 파일을 저장소에 쓰고 ③ 업무 트랜잭션에서 `stored`로 바꾼다. 지우기는 ① 행을 `purging`으로 바꿔 커밋하고 ② 저장소의 파일을 지우고 ③ 지워지면 행을 `purged`로 바꾸거나(흔적이 남는 지우기, ATT-5) 행을 지운다(영구 삭제, TRASH-3). 저장소에서 지우기가 실패해도 업무 요청은 성공한다 |
| ATT-11 | 첨부 정리 작업(하루 한 번): 만든 지 1시간이 지난 `pending` 행과 `purging` 행의 파일을 지우고 ATT-10의 ③을 한다. 파일이 이미 없으면 지운 것으로 본다. 저장소를 훑어 DB에 없는 파일을 찾아 지우는 방식은 쓰지 않는다(복원 직후처럼 DB가 뒤처진 때 살아 있는 파일을 지울 수 있다). 정리 작업마다 돈 시각과 지운 수를 남긴다(OPS-2) |

## 18. 운영 경보와 시스템 상태 (OPS)

관측 도구는 선택이라 거기에만 기대지 않는다. 메일이 멈췄을 수 있으므로 경보는 메일이 아니라 관리자 알림함으로 남긴다.

| ID | 규칙 |
|---|---|
| OPS-1 | 운영 경보: 다음 일이 생기면 관리자 전부의 알림함에 항목을 남긴다. 보내기 대기에 `failed` 행이 생김(MAIL-6 포함), 한 채널의 보내기 실패가 한 시간 넘게 이어짐, 정리 작업(OPS-2) 하나가 하루 넘게 돌지 않음, 첨부 총량이 한도의 80%를 넘음(ATT-3), 문의 자동 정리가 처음 켜짐(PII-6), 내보내기 실패(PII-8), 메일 받기 회차가 한 시간 넘게 실패함과 받은 메일 격리(12번, `../slices/12-mailin/acceptance.md` S12-AC-37). 같은 종류의 경보는 풀리기 전까지 하루에 한 건만 남긴다 |
| OPS-2 | 정리 작업(보내기 대기의 `failed` 행, 임시 이미지, 첨부 파일, 세션, 받은·보낸 메일의 `Message-ID`, 비밀번호 재설정 토큰, `Idempotency-Key`, 감사 로그 보관, 문의 자동 정리, 휴지통 영구 삭제)마다 마지막으로 돈 시각과 지운 수를 DB에 남기고 지표로도 낸다 |

메일 받기의 줄은 12번에서 채운다. OIDC의 시스템 상태 줄은 11번 묶음 `acceptance.md` S11-AC-30에 있다.
