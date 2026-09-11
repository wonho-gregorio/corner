# Gym Management System 위키

Gym Management System은 도장 운영에 필요한 회원, 수강권, 출석, 수업, 결제와 알림을 한곳에서 관리하기 위한 프로젝트입니다.

현재 저장소는 **기술 기반과 업무 모듈 경계가 마련된 초기 개발 단계**입니다. 프론트엔드는 Vite 기본 화면, 백엔드는 Spring Modulith 모듈 선언과 PostgreSQL 연결 설정까지 구성되어 있습니다. API, 엔티티, 화면과 Flyway 마이그레이션은 앞으로 구현해야 합니다.

## 빠른 탐색

| 문서 | 내용 |
| --- | --- |
| [시작하기](getting-started.md) | 요구 사항, 설치, 실행과 상태 확인 |
| [아키텍처](architecture.md) | 시스템 구성과 모듈 경계 원칙 |
| [제품 요구사항](product-requirements.md) | 1차 MVP 범위와 합의된 회원·회원권·결제·출석·알림 정책 |
| [화면 구조와 업무 흐름](navigation.md) | 관리자·직원 메뉴, 주요 화면 구성과 화면 이동 흐름 |
| [업무 모듈](modules.md) | 각 도메인의 책임과 협력 관계 |
| [API 및 데이터 모델](api-and-data.md) | API·DB 문서화 규칙과 현재 상태 |
| [개발 가이드](development.md) | 패키지 구성, 검증, 코드 변경 절차 |
| [운영 및 문제 해결](operations.md) | 환경 변수, 헬스 체크와 흔한 오류 |
| [로드맵](roadmap.md) | 구현 순서와 완료 기준 |

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| 프론트엔드 | React 19, TypeScript 6, Vite 8 |
| 백엔드 | Java 21, Spring Boot 4.1, Spring Modulith 2.1 |
| 데이터 | PostgreSQL 17, Spring Data JPA, Flyway |
| 인증·검증 | Spring Security, Bean Validation |
| 개발 환경 | Gradle Wrapper, npm, Docker Compose |

## 저장소 구조

```text
GymManagementSystem/
├── backend/                 # Spring Boot 애플리케이션
│   └── src/main/java/com/gym/management/
│       ├── auth/
│       ├── member/
│       ├── membership/
│       ├── attendance/
│       ├── lesson/
│       ├── payment/
│       └── notification/
├── frontend/                # React 애플리케이션
├── docs/                    # 프로젝트 위키
├── compose.yml              # 로컬 PostgreSQL
└── README.md                # 프로젝트 요약과 빠른 시작
```

## 문서 관리 원칙

- 코드와 문서는 같은 변경 사항에서 함께 갱신합니다.
- 아직 구현되지 않은 내용은 `예정`이라고 명시합니다.
- 새 API, 테이블, 환경 변수를 추가하면 관련 위키 문서도 수정합니다.
- 명령 예시는 저장소 루트에서 실행하는 것을 기준으로 작성합니다.

[저장소 README로 돌아가기](../README.md)
