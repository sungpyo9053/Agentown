# Production runbook

이 문서는 서버 구매 뒤 입력해야 할 값과 배포 절차만 남긴 운영 체크리스트다. 기준 구성은 Ubuntu 기반 AWS Lightsail/EC2 2 vCPU, 4 GB RAM, 80 GB SSD 한 대와 Docker Compose다.

## 서버와 DNS

1. Ubuntu LTS 서버를 만들고 고정 IP를 연결한다.
2. 방화벽은 SSH 관리 주소, TCP 80, TCP/UDP 443만 허용한다. 3000, 8080, 5432는 공개하지 않는다.
3. 도메인 A/AAAA 레코드를 고정 IP로 연결한다.
4. Docker Engine과 Compose plugin을 설치하고 `/opt/agentown`에 저장소를 clone한다.

## 비밀 값

```bash
cd /opt/agentown
cp deploy/.env.production.example deploy/.env.production
openssl rand -base64 32
openssl rand -base64 36
chmod 600 deploy/.env.production
```

첫 번째 출력은 `LLM_MASTER_KEYS=v1:<출력>`에, 두 번째 출력은 `POSTGRES_PASSWORD`에 넣는다. 이 파일은 Git에 올리지 않는다. 이메일 발송 서비스 계약 후 HTTPS 웹훅 URL과 토큰을 입력한다. 사용자 LLM 키는 이 마스터 키로 AES-256-GCM 암호화되며 원문은 조회 API, 로그, 복제본에 포함되지 않는다.

운영 테스트 계정이 서버 공용 Codex 로그인을 사용하는 경우 `deploy/codex-auth/auth.json`을 컨테이너의 `app` 사용자만 읽을 수 있게 둔다. 이 디렉터리는 Git에 포함되지 않는 런타임 비밀정보다. 소스 동기화나 `rsync --delete` 시에는 반드시 `deploy/.env.production`과 함께 `deploy/codex-auth/`를 제외하고, 이미지 갱신 뒤 컨테이너에서 `/home/app/.codex/auth.json`을 읽을 수 있는지 확인한다. 인증 파일의 내용은 로그나 배포 산출물에 출력하지 않는다.

## 배포와 점검

```bash
docker compose --env-file deploy/.env.production -f docker-compose.production.yml config --quiet
docker compose --env-file deploy/.env.production -f docker-compose.production.yml up -d --build
docker compose --env-file deploy/.env.production -f docker-compose.production.yml ps
curl -fsS https://YOUR_DOMAIN/health
```

아카이브를 서버에 동기화하는 배포라면 런타임 파일 보존 조건은 다음과 같다.

```bash
rsync -a --delete \
  --exclude deploy/.env.production \
  --exclude deploy/codex-auth/ \
  RELEASE_DIR/ /opt/agentown/
docker compose --env-file deploy/.env.production -f docker-compose.production.yml up -d --build
docker compose --env-file deploy/.env.production -f docker-compose.production.yml exec -T backend \
  sh -c 'test -r /home/app/.codex/auth.json'
```

Caddy가 인증서를 자동 발급하고 HTTPS만 외부에 제공한다. Backend와 PostgreSQL은 Compose 내부 네트워크에서만 접근 가능하며 Backend CORS는 `DOMAIN`에서 만든 HTTPS Origin만 허용한다. 배포 후 회원가입, 로그인, BYOK 검증, Stub이 아닌 실제 1회 실행, 결과 다운로드를 운영 계정으로 확인한다.

## 백업과 복구

`deploy/backup-postgres.sh`를 매일 실행하고 생성된 암호화 전용 권한의 `.sql.gz` 파일을 서버 밖 저장소로 복사한다. 보존 삭제는 외부 백업이 확인된 뒤 운영자가 명시적으로 수행한다.

```bash
sudo AGENTOWN_PROJECT_DIR=/opt/agentown AGENTOWN_BACKUP_DIR=/opt/agentown-backups /opt/agentown/deploy/backup-postgres.sh
gzip -t /opt/agentown-backups/agentown-TIMESTAMP.sql.gz
```

복구는 새 빈 DB에서 먼저 연습한다. 서비스를 내리고 대상 DB를 명시적으로 확인한 뒤 `gunzip -c BACKUP | docker compose ... exec -T postgres psql ...`로 가져온다. 실DB 덮어쓰기는 검증된 외부 백업과 복구 창구가 확보됐을 때만 수행한다.

## 업데이트와 롤백

1. 현재 Git SHA와 DB 백업을 기록한다.
2. `git fetch` 후 배포할 SHA를 명시해 checkout한다.
3. Compose build/up 후 health와 핵심 브라우저 흐름을 확인한다.
4. 애플리케이션 문제가 있으면 이전 SHA로 돌아가 이미지를 재빌드한다. Flyway 마이그레이션이 적용됐다면 파일을 임의 수정하거나 삭제하지 말고 전진 마이그레이션으로 복구한다.

## 관측과 경보

- `/actuator/health`: 컨테이너 health check
- `/actuator/metrics/agentown.execution.queued`
- `/actuator/metrics/agentown.execution.completed`
- `/actuator/metrics/agentown.execution.duration`
- Docker JSON 로그에서 `executionId`, `userId`, `errorCode`를 기준으로 조사한다.

서버 디스크, DB 백업 실패, API 오류율, LLM 공급자 오류율, 일일 비용은 외부 모니터링에 연결한다. 서버 구매 전에는 실제 DNS, 인증서, 이메일 발송, 외부 백업 복구를 완료로 판정하지 않는다.
