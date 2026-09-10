# 운영 및 문제 해결

## 상태 확인

백엔드가 실행 중일 때 다음 엔드포인트로 상태를 확인합니다.

```bash
curl http://localhost:8080/actuator/health
```

Actuator에서 외부에 노출하도록 설정된 엔드포인트는 `health`와 `info`입니다.

## 주요 환경 변수

| 변수 | 용도 |
| --- | --- |
| `DB_URL` | PostgreSQL JDBC 주소 |
| `DB_USERNAME` | PostgreSQL 사용자 |
| `DB_PASSWORD` | PostgreSQL 비밀번호 |
| `SOLAPI_API_KEY` | SOLAPI API 키 |
| `SOLAPI_API_SECRET` | SOLAPI API 시크릿 |

운영 환경에서는 `compose.yml`의 개발용 비밀번호를 사용하지 않습니다. 비밀 관리 수단으로 값을 주입하고 로그나 오류 응답에 노출되지 않도록 합니다.

SOLAPI 자격 증명 파일과 실제 키 값은 저장소에 커밋하지 않습니다. 개발 환경의 실제 발송 테스트는 허용된 테스트 수신번호로 제한합니다.

## 흔한 문제

### 백엔드가 데이터베이스에 연결되지 않음

1. `docker compose ps`에서 `postgres`가 실행 중이고 `healthy`인지 확인합니다.
2. 호스트의 5432 포트를 다른 PostgreSQL이 사용 중인지 확인합니다.
3. `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`가 컨테이너 설정과 일치하는지 확인합니다.

### Flyway 또는 Hibernate 검증 오류

`ddl-auto: validate`이므로 엔티티와 실제 스키마가 다르면 시작에 실패합니다. 엔티티 변경에 대응하는 Flyway 마이그레이션이 있는지 확인합니다. 현재 초기 상태에는 업무 마이그레이션이 없으므로 엔티티를 먼저 추가하면 반드시 마이그레이션도 만들어야 합니다.

### 8080 또는 5173 포트가 이미 사용 중임

해당 포트를 사용 중인 프로세스를 종료하거나 애플리케이션 포트를 변경합니다. Vite는 다음처럼 다른 포트로 실행할 수 있습니다.

```bash
npm run dev -- --port 5174
```

### Gradle이 Java 버전을 찾지 못함

`java -version`으로 Java 21 설치 여부를 확인하고 `JAVA_HOME`이 Java 21 JDK를 가리키는지 확인합니다. 프로젝트는 Gradle Java Toolchain 21을 요구합니다.

### 프론트엔드 의존성 또는 빌드 오류

먼저 `frontend`에서 현재 잠금 파일 기준으로 의존성을 다시 설치합니다.

```bash
npm ci
npm run lint
npm run build
```

## 로컬 데이터 초기화

아래 명령은 로컬 PostgreSQL 볼륨과 그 안의 데이터를 삭제합니다. 필요한 데이터가 없는 개발 환경에서만 사용합니다.

```bash
docker compose down -v
docker compose up -d
```

## 운영 전 추가로 필요한 항목

- 운영 프로필과 비밀 관리
- HTTPS와 허용 출처(CORS) 정책
- 인증·인가 및 관리자 계정 초기화 절차
- 데이터베이스 백업·복구 절차
- 구조화 로그, 메트릭과 알림
- 개인정보 보관·삭제 및 감사 로그 정책

[위키 홈](index.md) · [시작하기](getting-started.md)
