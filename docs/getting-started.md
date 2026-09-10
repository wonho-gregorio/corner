# 시작하기

## 사전 요구 사항

- Java 21
- Node.js 20 이상과 npm
- Docker Desktop 또는 Docker Engine과 Docker Compose

버전을 확인합니다.

```bash
java -version
node --version
npm --version
docker compose version
```

## 1. 데이터베이스 실행

저장소 루트에서 PostgreSQL 컨테이너를 시작합니다.

```bash
docker compose up -d
docker compose ps
```

개발용 기본 접속 정보는 다음과 같습니다.

| 항목 | 값 |
| --- | --- |
| 호스트 | `localhost` |
| 포트 | `5432` |
| 데이터베이스 | `gym_management` |
| 사용자 | `gym` |
| 비밀번호 | `gym` |

이 값은 로컬 개발 전용입니다. 공유 환경이나 운영 환경에서는 반드시 별도 자격 증명을 사용합니다.

## 2. 백엔드 실행

macOS 또는 Linux:

```bash
cd backend
./gradlew bootRun
```

Windows PowerShell:

```powershell
Set-Location backend
./gradlew.bat bootRun
```

기본 주소는 `http://localhost:8080`입니다. 실행 후 상태를 확인합니다.

```bash
curl http://localhost:8080/actuator/health
```

정상이라면 응답의 상태가 `UP`으로 표시됩니다.

## 3. 프론트엔드 실행

다른 터미널을 열어 실행합니다.

```bash
cd frontend
npm install
npm run dev
```

터미널에 표시되는 주소로 접속합니다. 기본 주소는 `http://localhost:5173`입니다.

> 현재 프론트엔드는 Vite 기본 화면 단계이며, 업무 화면은 아직 구현되지 않았습니다.

## 4. 검증

백엔드 테스트:

```bash
cd backend
./gradlew test
```

프론트엔드 정적 검사와 빌드:

```bash
cd frontend
npm run lint
npm run build
```

## 종료

애플리케이션은 각 터미널에서 `Ctrl+C`로 종료합니다. PostgreSQL 컨테이너만 중지하려면 다음을 실행합니다.

```bash
docker compose stop
```

`docker compose down`은 컨테이너와 네트워크를 제거하지만 이름 있는 데이터 볼륨은 유지합니다. 데이터까지 삭제하는 `docker compose down -v`는 로컬 DB를 완전히 초기화할 때만 사용하세요.

[위키 홈](index.md) · [문제 해결](operations.md)
