# 개발 가이드

## 변경 작업 흐름

1. 변경할 업무 모듈과 데이터 소유권을 확인합니다.
2. 공개 계약과 내부 구현을 구분합니다.
3. 스키마 변경이 있으면 Flyway 마이그레이션을 먼저 정의합니다.
4. 백엔드 테스트와 프론트엔드 검사를 실행합니다.
5. API, 테이블, 환경 변수 또는 동작이 바뀌면 위키도 갱신합니다.

## 백엔드 패키지 규칙

- 기본 패키지: `com.gym.management`
- 업무 모듈의 공개 API: 각 모듈 루트 또는 명시적으로 공개한 패키지
- 컨트롤러, 엔티티, 리포지토리와 구현 클래스: 원칙적으로 해당 모듈의 `internal` 하위
- 다른 모듈의 `internal` 타입 직접 참조 금지

공개 타입은 다른 모듈이 실제로 사용해야 하는 최소 계약만 포함합니다. 데이터베이스 엔티티를 모듈 간 DTO처럼 전달하지 않습니다.

## 백엔드 검증

```bash
cd backend
./gradlew test
```

Windows PowerShell:

```powershell
Set-Location backend
./gradlew.bat test
```

현재 테스트에는 Spring Modulith의 모듈 경계 검증이 포함됩니다. 기능 추가 시 단위 테스트와 필요한 통합 테스트를 함께 추가합니다.

## 프론트엔드 검증

```bash
cd frontend
npm run lint
npm run build
```

- `npm run lint`: oxlint 정적 검사
- `npm run build`: TypeScript 프로젝트 빌드 후 Vite 프로덕션 번들 생성

## 환경 설정

백엔드 데이터베이스 설정은 환경 변수로 덮어쓸 수 있습니다.

| 환경 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/gym_management` | JDBC 주소 |
| `DB_USERNAME` | `gym` | DB 사용자 |
| `DB_PASSWORD` | `gym` | DB 비밀번호 |

비밀 값은 저장소에 커밋하지 않습니다. `.env` 파일도 Git에서 제외되어 있습니다.

## 문서 완료 조건

다음 중 하나가 바뀌면 코드 작업은 관련 문서 수정까지 포함해야 완료된 것으로 봅니다.

- 실행 명령이나 요구 버전
- 모듈 책임 또는 의존 관계
- API 요청·응답과 권한
- DB 테이블 또는 주요 컬럼
- 환경 변수와 운영 절차

[위키 홈](index.md) · [아키텍처](architecture.md) · [API 및 데이터 모델](api-and-data.md)
