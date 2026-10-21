# 실패 확인

- 단언 실패 16, 통과 0, 허용한 통과 3, 오류 0, 건너뜀 0
- 판정: 통과

## 처음부터 통과해도 된다고 허용한 테스트 (점검에서 이유를 본다)

- `io.popdesk.audit.AuditRetentionBatchTest.entryBehindOneInsideThePeriodStaysInBatches`: 지금 코드는 끝을 한 번만 정한다(lastOfFrontBefore). 묶음마다 끝을 다시 찾는 구현을 잡으려는 테스트다
- `io.popdesk.audit.AuditRetentionBatchTest.oneRunDeletesMoreThanABatch`: 지금 코드는 한 문장으로 기간 지난 앞 항목을 모두 지운다. 한 실행에 1,000개 넘게 지우기와 deleted_count는 이미 맞다
- `io.popdesk.mailin.UnreadableMailTest.connectionGoneWhileReading(Request)[1]`: 원문을 받는 중에 연결이 끊기면 지금도 회차가 CONNECT로 실패하고 시도 수를 세지 않는다(B7-AC-14는 지금 동작을 지키라는 기준)

## 단언에서 실패한 테스트

- `io.popdesk.audit.AuditRetentionBatchTest.firstBatchIsCommittedWhileTheSecondRuns`: [seq of the renames in the audit list while the second batch is held; the first batch ended at 3214] 
- `io.popdesk.audit.AuditRetentionBatchTest.failedBatchLeavesTheOnesBeforeIt`: [seq of the renames in the audit list after the second batch failed; the first batch ended at 4320] 
- `io.popdesk.audit.AuditRetentionBatchTest.anonymizationBetweenBatches`: [status of the anonymization sent during the second batch: {"code":"ANONYMIZATION_RUNNING","message":"Another account is being anonymized."}] 
- `io.popdesk.mailin.UnreadableMailLogTest.warningWithTheUidOnly(Refused)[1]`: [warnings of round 1 with the UID 1] 
- `io.popdesk.mailin.UnreadableMailLogTest.warningWithTheUidOnly(Refused)[2]`: Timed out after PT30S waiting for a mail-in round at 2026-10-08T03:17:00Z
- `io.popdesk.mailin.UnreadableMailLogTest.warningWithTheUidOnly(Refused)[3]`: [warnings of round 1 with the UID 3] 
- `io.popdesk.mailin.UnreadableMailTest.fiveSkipsThenQuarantine(boolean)[1]`: [folders after five rounds] 
- `io.popdesk.mailin.UnreadableMailTest.fiveSkipsThenQuarantine(boolean)[2]`: [folders after five rounds] 
- `io.popdesk.mailin.UnreadableMailTest.skippedMailIsCounted`: [folders of the unreadable mail] 
- `io.popdesk.mailin.UnreadableMailTest.noHeaderEither`: [folders after the first round] 
- `io.popdesk.mailin.UnreadableMailTest.unreadableMailResolvesStalled`: Timed out after PT30S waiting for a mail-in round at 2026-10-11T07:41:00Z
- `io.popdesk.mailin.UnreadableMailTest.unreadableMailIsSkipped(Unreadable)[1]`: [folders of the unreadable mail] 
- `io.popdesk.mailin.UnreadableMailTest.unreadableMailIsSkipped(Unreadable)[2]`: [folders of the unreadable mail] 
- `io.popdesk.mailin.UnreadableMailTest.unreadableMailIsSkipped(Unreadable)[3]`: Timed out after PT30S waiting for a mail-in round at 2026-10-11T07:44:00Z
- `io.popdesk.mailin.UnreadableMailTest.skippedMailWithAnOutcomeIsMoved`: [copies in the quarantine folder] 
- `io.popdesk.mailin.UnreadableMailTest.connectionGoneWhileReading(Request)[2]`: Timed out after PT30S waiting for a failed mail-in round at 2026-10-11T07:49:00Z
