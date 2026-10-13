# 실패 확인

- 단언 실패 55, 통과 0, 허용한 통과 7, 오류 0, 건너뜀 0
- 판정: 통과

## 처음부터 통과해도 된다고 허용한 테스트 (점검에서 이유를 본다)

- `io.popdesk.attachment.StorageClientRuleTest.onlyTheStoragePackageUsesTheS3Client`: 구조 규칙이다. S3 클라이언트를 쓰는 운영 코드가 아직 없어 어기는 곳도 없다. 구현이 저장소 패키지 밖에서 쓰면 실패한다
- `io.popdesk.start.StorageStartCheckTest.aFileInTheFoldersPlaceStopsTheStart`: 지금은 disk면 까닭과 상관없이 뜨지 않는다. 단언은 "뜨지 않는다"와 "그 파일이 그대로다"뿐이라 통과한다. 구현 뒤에는 폴더 검사가 이 단언을 지킨다
- `io.popdesk.start.StorageStartCheckTest.dbOnlyTouchesNoOtherStorage`: db만 쓰는 설치는 지금도 다른 저장소에 닿지 않는다(S5B-AC-27은 지금 동작을 지키는 기준이다)
- `io.popdesk.start.StorageStartCheckTest.unusableS3StopsTheStart(String)[1]`: 지금은 s3면 까닭과 상관없이 뜨지 않고 저장소에 닿지 않는다. 단언은 "뜨지 않는다", "비밀값이 없다", "버킷에 쓴 것이 없다"라 통과한다. 구현 뒤에는 뜰 때의 써 보기가 지킨다
- `io.popdesk.start.StorageStartCheckTest.unusableS3StopsTheStart(String)[2]`: 위와 같다(없는 버킷)
- `io.popdesk.start.StorageStartCheckTest.unusableS3StopsTheStart(String)[3]`: 위와 같다(아무도 듣지 않는 주소)
- `io.popdesk.start.StorageStartCheckTest.badShapeStopsTheStart(String)[1]`: `POPDESK_STORAGE=file`은 지금의 설정 검사가 이미 키 이름을 대며 막는다

## 단언에서 실패한 테스트

- `io.popdesk.start.DiskStorageTest.listDoesNotReadTheStorage`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.DiskStorageTest.deletingAFileThatIsGone`: Earlier, starting the app with POPDESK_STORAGE=disk failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creat
- `io.popdesk.start.DiskStorageTest.answersAsWithDb`: Earlier, starting the app with POPDESK_STORAGE=disk failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creat
- `io.popdesk.start.DiskStorageTest.missingFileIsAnError`: Earlier, starting the app with POPDESK_STORAGE=disk failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creat
- `io.popdesk.start.DiskStorageTest.namesNeverReachThePath(String)[1]`: Earlier, starting the app with POPDESK_STORAGE=disk failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creat
- `io.popdesk.start.DiskStorageTest.namesNeverReachThePath(String)[2]`: Earlier, starting the app with POPDESK_STORAGE=disk failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creat
- `io.popdesk.start.DiskStorageTest.namesNeverReachThePath(String)[3]`: Earlier, starting the app with POPDESK_STORAGE=disk failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creat
- `io.popdesk.start.DiskStorageTest.firstStartLeavesTheMarker`: Earlier, starting the app with POPDESK_STORAGE=disk failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creat
- `io.popdesk.start.DiskStorageTest.newFilesGoToTheFolder`: Earlier, starting the app with POPDESK_STORAGE=disk failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creat
- `io.popdesk.start.S3StorageTest.listDoesNotReadTheStorage`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.S3StorageTest.deletingAFileThatIsGone`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.answersAsWithDb`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.missingFileIsAnError`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.namesNeverReachThePath(String)[1]`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.namesNeverReachThePath(String)[2]`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.namesNeverReachThePath(String)[3]`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.aSilentStorageDoesNotHoldAnUploadForever`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.secretsStayOutOfLogAndAnswers`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.firstStartLeavesTheMarker`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.failedRemovalIsFinishedByTheCleanup`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.failedWriteIsAnErrorAndNotListed`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.newFilesGoToTheBucket`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.httpEndpointIsWarnedAbout`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.downloadsComeFromTheApp`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.S3StorageTest.anUnreachableStorageDoesNotHoldTheOtherJobs`: Earlier, starting the app with POPDESK_STORAGE=s3 failed: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creatin
- `io.popdesk.start.StorageStartCheckTest.s3RowsNeedTheS3Keys(String, String)[1]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageStartCheckTest.s3RowsNeedTheS3Keys(String, String)[2]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageStartCheckTest.s3RowsNeedTheS3Keys(String, String)[3]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageStartCheckTest.anEmptyFolderWithRowsStopsTheStart`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageStartCheckTest.purgedRowsDoNotNeedTheMarker(String)[1]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageStartCheckTest.purgedRowsDoNotNeedTheMarker(String)[2]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageStartCheckTest.rowsWithoutTheirMarkerStopTheStart(String, String)[1]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageStartCheckTest.rowsWithoutTheirMarkerStopTheStart(String, String)[2]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageStartCheckTest.rowsWithoutTheirMarkerStopTheStart(String, String)[3]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageStartCheckTest.rowsWithoutTheirMarkerStopTheStart(String, String)[4]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageStartCheckTest.anExistingMarkerIsNotWrittenAgain(String)[1]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageStartCheckTest.anExistingMarkerIsNotWrittenAgain(String)[2]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageStartCheckTest.badShapeStopsTheStart(String)[2]`: [what the start told names POPDESK_STORAGE_DIR] 
- `io.popdesk.start.StorageStartCheckTest.badShapeStopsTheStart(String)[3]`: [what the start told names S3_BUCKET] 
- `io.popdesk.start.StorageStartCheckTest.badShapeStopsTheStart(String)[4]`: [what the start told names S3_ACCESS_KEY] 
- `io.popdesk.start.StorageStartCheckTest.badShapeStopsTheStart(String)[5]`: [what the start told names S3_SECRET_KEY] 
- `io.popdesk.start.StorageStartCheckTest.badShapeStopsTheStart(String)[6]`: [what the start told names S3_REGION] 
- `io.popdesk.start.StorageSwitchTest.temporaryImageCleanupRemovesFromTheOldPlace`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageSwitchTest.attachmentCleanupRemovesFromTheOldPlace`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageSwitchTest.oldFilesStayWhereTheirRowsSay(String, String)[1]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageSwitchTest.oldFilesStayWhereTheirRowsSay(String, String)[2]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageSwitchTest.oldFilesStayWhereTheirRowsSay(String, String)[3]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageSwitchTest.oldFilesStayWhereTheirRowsSay(String, String)[4]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.start.StorageSwitchTest.oldFilesStayWhereTheirRowsSay(String, String)[5]`: The app did not start: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'attachmentService' defined in file [C:\U
- `io.popdesk.deploy.AttachmentsCheckCommandTest.anotherArgumentIsAUsageError`: [standard error] 
- `io.popdesk.deploy.AttachmentsCheckCommandTest.reportsWhatIsMissingAndChangesNothing`: [exit code on an installation without attachments
- `io.popdesk.deploy.AttachmentsCheckCommandTest.endsWithOneWhenTheDatabaseIsOutOfReach`: [standard error] 
- `io.popdesk.deploy.ReleaseComposeStorageTest.s3KeysArePassedOnlyWhenSet`: [S3_ENDPOINT in the app's environment when it is set] 
- `io.popdesk.deploy.S3AttachmentLoadTest.sixteenUploadsAndDownloadsAtOnce`: Timed out after PT2M waiting for app healthy
- `io.popdesk.deploy.StorageFolderOwnerTest.aFolderOfAnotherOwnerStopsTheStart`: Timed out after PT3M waiting for the app's log naming the user ID 10001
