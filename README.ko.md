# popdesk

지원팀 하나가 여러 고객사의 문의를 받는 설치형 헬프데스크입니다.
고객은 고객 포털에서 문의(문서와 API에서는 issue)를 올리고 진행을 보고, 상담원은 상담 콘솔에서 답하고 내부 작업을 기록합니다.

[English](README.md) · 영어판이 정본입니다. 둘이 다르면 영어판을 따릅니다.

> **지금은 0.5.0입니다.** 고객사와 구성원, 첨부와 본문 이미지가 있는 문의, 멘션이 되는 댓글, 알림함, 메일과 팀 웹훅, 해시 사슬로 이어진 감사 로그가 있습니다. 0.5.0에서 첨부의 폴더·S3 저장, 라벨, 마감일, 내부 작업, 문의 연결, 휴지통과 자동 종료, 고객사별 건수, SLA 기한과 알림, 브랜딩, 개인정보 내보내기와 익명화, OIDC 로그인, 메일로 문의 받기, 영어 화면이 들어갔습니다. 이미지는 아직 레지스트리에 올리지 않았으니 태그에서 직접 빌드합니다([릴리스 노트](docs/release-notes/0.5.0.md)). 운영에 쓸 수 있는 상태가 아닙니다. 계획과 결정은 [`design/`](design/)에 있습니다.

## 목표

- **컨테이너 둘.** 앱 이미지 하나와 PostgreSQL 하나. `docker compose up`이면 헬프데스크가 뜹니다.
- **작은 PC에서도.** 앱과 DB가 메모리 1GB 남짓을 씁니다. 16GB 노트북에서 개발합니다.
- **분명한 경계.** 고객은 다른 고객사의 문의를 볼 수 없습니다. 내부 댓글과 담당자는 고객에게 보이지 않습니다.
- **남는 기록.** 문의마다 대화, 상태 이력, 내부 작업 기록이 한곳에 남고, 관리 작업은 감사 로그에 남습니다.

## 써 보기

이미지를 아직 올리지 않았으니 0.5.0을 태그에서 빌드합니다. Git과 Compose가 있는 Docker가 필요합니다.

```
git clone https://github.com/IMMINJU/popdesk.git
cd popdesk
git checkout v0.5.0
docker build -t ghcr.io/imminju/popdesk:0.5.0 .
cat > deploy/.env <<'EOF'
POPDESK_DB_PASSWORD=choose-a-database-password
POPDESK_ADMIN_EMAIL=admin@example.com
POPDESK_ADMIN_PASSWORD=choose-a-long-admin-password
EOF
docker compose -f deploy/compose.yml up -d
```

관리자 비밀번호는 10~72자입니다. 그다음 `http://console.localhost:8080`을 열어 관리자로 로그인합니다. 고객은 `http://portal.localhost:8080`을 씁니다. 이 주소는 서버와 같은 PC의 브라우저에서만 열립니다. 메일 설정이 없으면 메일은 나가지 않고, 콘솔이 초대 링크를 보여 주니 직접 전하면 됩니다.

서버에 자기 호스트 이름, TLS, 메일까지 갖춰 설치하려면 [Installation](docs/operate/installation.md)을 따르세요(영어). 누가 무엇을 할 수 있는지(관리자, 상담원, 고객, 고객사 담당자)는 [permissions.md](docs/use/permissions.md)에 있습니다.

## 하지 않을 것

채팅·전화·메신저 채널, 자가 가입, 실시간 푸시와 브라우저 알림, 네이티브 앱과 오프라인 동작, 고객사별 브랜딩, 설치 하나에 여러 팀(당분간). [`design/roadmap.md`](design/roadmap.md)를 보세요.

## 레포 짜임

| 경로 | 읽는 사람 | 언어 |
|---|---|---|
| `docs/` | 설치·사용·연동·기여하는 사람. 문서 사이트가 이 폴더를 읽습니다 | 영어 |
| `design/` | popdesk를 만드는 사람: 결정 기록(ADR), 로드맵, 진행 절차, 위협 모델, 진행 중인 작업. 사이트에 올리지 않습니다 | 한국어 |
| `server/`, `web/`, `deploy/` | 코드: 서버, 웹 앱 둘, Compose 파일 | |

## 기여

[CONTRIBUTING.md](CONTRIBUTING.md)와 [AI_POLICY.md](AI_POLICY.md)를 읽어 주세요. 보안 문제는 공개 이슈가 아니라 [SECURITY.md](SECURITY.md)의 방법으로 알려 주세요.

## 라이선스

[Apache License 2.0](LICENSE)
