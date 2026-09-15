# API 및 데이터 모델

## 현재 상태

PostgreSQL 업무 스키마와 JPA 영속 엔티티 구현을 완료했고, 최초 도장 설정과 직원 인증 API를 구현했습니다. 회원·회원권·결제·출석 등 나머지 업무 API는 후속 구현 범위입니다.

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| `GET` | `/actuator/health` | 애플리케이션 상태 확인 |
| `GET` | `/actuator/info` | 애플리케이션 정보 확인 |
| `GET` | `/api/setup/status` | 최초 설정 완료 여부 확인 |
| `POST` | `/api/setup` | 최초 도장과 관리자 계정 생성 |
| `POST` | `/api/auth/login` | 직원 로그인 |
| `POST` | `/api/auth/refresh` | 접근·갱신 토큰 회전 발급 |
| `POST` | `/api/auth/logout` | 갱신 토큰 폐기 |
| `GET` | `/api/auth/me` | 현재 직원·역할·권한 확인 |
| `POST` | `/api/auth/change-password` | 본인 비밀번호 변경과 기존 세션 만료 |
| `GET` | `/api/staff` | 관리자용 직원 목록 |
| `POST` | `/api/staff` | 직원 계정 생성과 일회성 임시 비밀번호 발급 |
| `GET` | `/api/staff/{accountId}` | 직원 계정 상세 |
| `PUT` | `/api/staff/{accountId}` | 이름·로그인 아이디·역할·상태·권한 변경 |
| `POST` | `/api/staff/{accountId}/temporary-password` | 임시 비밀번호 재발급과 기존 세션 만료 |
| `GET` | `/api/settings/gym` | 도장 정보와 퇴실 시간 사용 정책 조회 |
| `PUT` | `/api/settings/gym` | 도장명·연락처·주소·퇴실 시간 사용 정책 수정 |
| `GET` | `/api/member-groups` | 회원 그룹 목록 조회 |
| `POST` | `/api/member-groups` | 회원 그룹 생성 |
| `PUT` | `/api/member-groups/{groupId}` | 회원 그룹명·순서·상태 수정 |

`setup`, `setup/status`, `login`, `refresh`, `logout`, 상태 확인을 제외한 경로에는 `Authorization: Bearer <access-token>`이 필요합니다. 접근 토큰은 15분, 갱신 토큰은 30일이 기본이며 갱신 시 기존 토큰을 즉시 폐기합니다. 계정 상태·역할·권한과 `session_version`을 요청마다 DB에서 다시 확인하므로 계정 비활성화와 비밀번호 변경이 기존 접근 토큰에도 즉시 반영됩니다.

최초 설정은 PostgreSQL advisory lock 안에서 한 번만 실행되며 이후 요청은 `409 Conflict`입니다. 로그인 아이디는 영문·숫자·점·밑줄·하이픈 4~50자, 비밀번호는 10~72자를 허용합니다. 임시 비밀번호 변경이 필요한 직원은 `me`, `change-password`, `logout` 외 업무 API를 사용할 수 없습니다.

직원 관리 API는 `ADMIN` 전용입니다. 계정 활성화·비활성화는 `PUT` 요청의 `status` 값만 사용하며 별도 비활성화 엔드포인트는 두지 않습니다. 직원에게는 `MEMBER_MANAGE`, `ATTENDANCE_PROCESS`, `PAYMENT_REGISTER`만 부여할 수 있고 관리자 권한은 전체로 고정됩니다. 마지막 활성 관리자를 직원으로 변경하거나 비활성화하려는 요청은 도장 행을 잠근 상태에서 검사하고 `409 Conflict`로 거부합니다. 생성·재발급된 임시 비밀번호는 해당 응답에서 한 번만 제공하며 DB와 감사 로그에는 해시나 원문을 노출하지 않습니다.

도장 설정과 회원 그룹 관리 API도 `ADMIN` 전용입니다. 회원 그룹명은 도장 안에서 대소문자를 무시하고 중복될 수 없으며 그룹은 물리 삭제하지 않고 `ACTIVE`, `INACTIVE` 상태로 관리합니다. 도장 설정과 그룹 생성·수정은 처리자·처리 시각과 변경 전후 값을 감사 로그에 남깁니다.

## API 작성 규칙

업무 API를 추가할 때 이 문서에 다음 정보를 기록합니다.

- HTTP 메서드와 경로
- 필요한 역할 또는 권한
- 경로·쿼리·본문 매개변수
- 성공 응답과 대표 오류 응답
- 멱등성 또는 중복 요청 처리 방식

권장 형식:

```markdown
### 회원 등록

`POST /api/members`

- 권한: `ADMIN`
- 요청: 이름, 연락처, 보호자 정보
- 성공: `201 Created`
- 오류: `400` 입력 오류, `409` 중복 회원
```

API 경로와 오류 응답 형식은 첫 업무 API를 구현할 때 프로젝트 공통 규약으로 확정합니다.

## 데이터베이스 변경 규칙

- 모든 스키마 변경은 `backend/src/main/resources/db/migration/` 아래 Flyway SQL로 관리합니다.
- 파일명은 `V<버전>__<설명>.sql` 형식을 사용합니다. 예: `V1__create_member_table.sql`.
- 이미 공유된 환경에서 실행된 마이그레이션 파일은 수정하지 않고 새 버전을 추가합니다.
- 엔티티 변경과 대응 마이그레이션을 같은 변경 사항에 포함합니다.
- 테이블 소유 모듈을 명확히 하고 다른 모듈이 해당 테이블을 직접 갱신하지 않도록 합니다.

## 데이터 사전

테이블을 추가하면 아래 표에 기록합니다.

| 테이블 영역 | 소유 모듈 | 설명 | 상태 |
| --- | --- | --- | --- |
| `gyms`, `staff_*`, `auth_refresh_sessions`, `audit_logs`, `outbox_events` | `auth` | 도장 설정, 계정·권한, 인증 세션과 감사·이벤트 | V1 구현 완료 |
| `member_*`, `members`, `guardians` | `member` | 회원, 그룹, 보호자, 가족관계, 동의와 상태 이력 | V1 구현 완료 |
| `membership_products`, `promotions`, `promotion_products`, `memberships`, `membership_pauses`, `membership_events`, `membership_count_entries` | `membership` | 상품, 행사, 발급 회원권, 휴회, 날짜·횟수 원장 | V2 구현 완료 |
| `membership_terminations` | `membership` | 결제 환불과 선택적으로 연결되는 회원권 해지 원장 | V3 구현 완료 |
| `charges`, `charge_*`, `payment_*` | `payment` | 청구, 납부 계획, 결제·취소·정정·환불 원장 | V3 구현 완료 |
| `lesson_*` | `lesson` | 반복 수업과 날짜별 실제 수업 | V4 구현 완료 |
| `attendances`, `attendance_*` | `attendance` | 출석 원기록, 취소·시간 정정과 예외 승인 | V4 구현 완료 |
| `notification_*` | `notification` | 문자 설정 버전, 발송 작업과 공급자 시도 이력 | V5 구현 완료 |

개인정보를 저장할 때는 보관 기간, 마스킹, 접근 권한과 삭제 정책도 함께 정의해야 합니다.

전체 컬럼, 상태 코드, 검사 제약, 인덱스와 잠금 순서는 [데이터베이스 설계](database-design.md)를 따릅니다.

[위키 홈](index.md) · [업무 모듈](modules.md) · [개발 가이드](development.md)
