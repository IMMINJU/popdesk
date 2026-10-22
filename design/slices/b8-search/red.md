# 실패 확인

- 단언 실패 4, 통과 0, 허용한 통과 12, 오류 0, 건너뜀 0
- 판정: 통과

## 처음부터 통과해도 된다고 허용한 테스트 (점검에서 이유를 본다)

- `io.popdesk.deploy.ShortSearchUpgradeTest.shortSearchFindsWhat050Found`: 0.5.0 이미지가 0.5.0 견본에서 찾는 것과 같다(B8-AC-06의 결과 쪽)
- `io.popdesk.issue.ShortSearchTest.oneOrTwoCharactersFindWhatTheyDidBefore(App)[1]`: 지금 코드는 1~2자도 ilike로 찾아 결과가 이미 맞다. B8-AC-01은 결과가 바뀌지 않아야 한다는 기준이다
- `io.popdesk.issue.ShortSearchTest.oneOrTwoCharactersFindWhatTheyDidBefore(App)[2]`: 같음
- `io.popdesk.issue.ShortSearchTest.shortSearchFollowsEditsAndPurges`: 같음(B8-AC-05)
- `io.popdesk.issue.ShortSearchTest.shortSearchKeepsTheCustomerScope(List)[1]`: 같음(B8-AC-03, 고객사 경계 음성 테스트)
- `io.popdesk.issue.ShortSearchTest.shortSearchKeepsTheCustomerScope(List)[2]`: 같음
- `io.popdesk.issue.ShortSearchTest.shortSearchKeepsTheCustomerScope(List)[3]`: 같음
- `io.popdesk.issue.ShortSearchTest.shortSearchKeepsTheCustomerScope(List)[4]`: 같음
- `io.popdesk.issue.ShortSearchTest.shortSearchKeepsTheCustomerScope(List)[5]`: 같음
- `io.popdesk.issue.ShortSearchTest.shortSearchCountsWhatItFinds(String)[1]`: 같음(B8-AC-04)
- `io.popdesk.issue.ShortSearchTest.shortSearchCountsWhatItFinds(String)[2]`: 같음
- `io.popdesk.issue.ShortSearchTest.shortSearchWithConsoleFiltersOnlyNarrows`: 같음(B8-AC-03)

## 단언에서 실패한 테스트

- `io.popdesk.deploy.ShortSearchRestoreTest.restoreBuildsTheGramIndex`: [issue_search_grams_idx in pg_index before the backup] 
- `io.popdesk.deploy.ShortSearchUpgradeTest.gramExpressionWorksWithAnEmptySearchPath`: [issue_search_grams_idx in pg_index] 
- `io.popdesk.deploy.ShortSearchUpgradeTest.migrationMakesAnImmutableGramIndex`: [issue_search_grams_idx in pg_index after the upgrade] 
- `io.popdesk.issue.ShortSearchTest.gramIndex`: [indexes of issue] 
