# Gym Management System

도장 회원 관리를 위한 React + Spring Boot 모듈형 모놀리스 프로젝트입니다.

> 프로젝트의 구조, 개발 규칙, 실행 방법은 **[프로젝트 위키](docs/index.md)**에서 자세히 확인할 수 있습니다.

## 구조

- `frontend`: React + TypeScript + Vite
- `backend`: Java 21 + Spring Boot + Spring Modulith + PostgreSQL
- `compose.yml`: 로컬 PostgreSQL

백엔드는 다음 업무 모듈로 나뉩니다.

- `auth`: 로그인과 권한
- `member`: 회원과 보호자 정보
- `membership`: 수강권, 기간, 횟수
- `attendance`: 출석과 퇴실
- `lesson`: 수업과 시간표
- `payment`: 결제, 환불, 미수금
- `notification`: 알림 발송

각 모듈의 루트 패키지는 다른 모듈에 공개되는 API 영역입니다. 구현 클래스, JPA 엔티티,
리포지토리는 각 모듈의 `internal` 하위 패키지에 둡니다. 다른 모듈의 `internal` 패키지를
직접 참조하면 아키텍처 테스트가 실패합니다.

## 로컬 실행

### 사전 요구 사항

- Java 21
- Node.js 20 이상
- Docker 및 Docker Compose

```bash
docker compose up -d
cd backend
./gradlew bootRun
```

별도 터미널에서 프론트엔드를 실행합니다.

```bash
cd frontend
npm install
npm run dev
```

Windows PowerShell에서는 백엔드 실행 명령으로 `./gradlew.bat bootRun`을 사용할 수 있습니다.

프론트엔드 기본 주소는 `http://localhost:5173`, 백엔드 기본 주소는
`http://localhost:8080`입니다. 백엔드 상태는 `/actuator/health`에서 확인할 수 있습니다.

## 검증

```bash
cd backend
./gradlew test
```

`GymManagementApplicationTests`가 모듈 순환 참조와 내부 패키지 침범을 검사합니다.

## 문서

- [위키 홈](docs/index.md)
- [시작하기](docs/getting-started.md)
- [아키텍처](docs/architecture.md)
- [업무 모듈](docs/modules.md)
- [API 및 데이터 모델](docs/api-and-data.md)
- [개발 가이드](docs/development.md)
- [운영 및 문제 해결](docs/operations.md)
- [로드맵](docs/roadmap.md)
