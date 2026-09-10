# 아키텍처

## 전체 구성

```text
사용자
  │
  ▼
React + TypeScript (frontend, :5173)
  │ HTTP/JSON — API 구현 예정
  ▼
Spring Boot 모듈형 모놀리스 (backend, :8080)
  │ JPA / Flyway
  ▼
PostgreSQL (:5432)
```

배포 단위는 프론트엔드, 백엔드와 데이터베이스로 나뉘지만 백엔드 업무 로직은 하나의 애플리케이션 안에서 명확한 모듈 경계를 유지합니다. 초기 프로젝트에서 운영 복잡도를 낮추면서도 업무 영역별 결합을 통제하려는 구조입니다.

## 백엔드 모듈 경계

최상위 업무 패키지 하나가 Spring Modulith의 애플리케이션 모듈 하나에 대응합니다.

```text
com.gym.management
├── auth
├── member
├── membership
├── attendance
├── lesson
├── payment
└── notification
```

각 모듈의 루트 패키지는 다른 모듈에 공개할 API 영역입니다. 외부에 공개하지 않을 구현은 `internal` 아래에 둡니다.

```text
member/
├── package-info.java        # 모듈 선언
├── MemberService.java       # 다른 모듈에서 사용할 수 있는 공개 API 예시
└── internal/
    ├── MemberEntity.java
    ├── MemberRepository.java
    └── MemberServiceImpl.java
```

### 의존 규칙

- 다른 모듈의 `internal` 패키지를 직접 참조하지 않습니다.
- 모듈 간 호출은 상대 모듈의 공개 타입을 통해 수행합니다.
- 모듈 간 결합을 낮춰야 하는 후속 처리는 애플리케이션 이벤트 사용을 우선 검토합니다.
- 양방향 모듈 의존과 순환 참조를 만들지 않습니다.
- JPA 엔티티와 리포지토리는 모듈 외부에 공개하지 않는 것을 기본으로 합니다.

`GymManagementApplicationTests`는 `ApplicationModules.verify()`를 실행하여 순환 의존과 내부 패키지 침범을 검사합니다.

## 데이터 관리

- PostgreSQL을 영속 저장소로 사용합니다.
- 스키마 변경은 Flyway 버전 마이그레이션으로 기록합니다.
- Hibernate의 `ddl-auto`는 `validate`이므로 애플리케이션이 스키마를 임의 생성하거나 변경하지 않습니다.
- `open-in-view`가 꺼져 있으므로 필요한 연관 데이터는 서비스 트랜잭션 안에서 조회합니다.

현재 마이그레이션과 업무 테이블은 아직 없습니다. 최초 엔티티를 추가할 때 대응하는 Flyway 마이그레이션도 같은 변경에 포함해야 합니다.

## 프론트엔드 방향

프론트엔드는 React 함수형 컴포넌트와 TypeScript를 사용합니다. 기능이 추가되면 업무 단위로 화면, API 클라이언트와 상태를 함께 배치하는 구성을 권장합니다.

```text
src/
├── app/                     # 앱 진입점, 라우팅, 전역 설정
├── features/                # member, attendance 등 업무 기능
├── shared/                  # 공용 UI, 유틸리티, HTTP 클라이언트
└── main.tsx
```

이 구조는 권장안이며 아직 저장소에 적용되지 않았습니다.

[위키 홈](index.md) · [업무 모듈](modules.md) · [개발 가이드](development.md)
