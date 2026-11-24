# 팔레트 (Palette)

> 다양한 셀러의 뷰티·의류 상품으로 나만의 취향을 완성하는 입점형 이커머스 플랫폼

여러 판매자가 입점해 상품을 팔고, 플랫폼이 중개·결제·정산을 맡습니다. 주문은 **예치금**으로 결제하고, 결제 금액은 구매 확정 전까지 플랫폼이 보관(홀딩)했다가 매월 10일 수수료 10%를 뺀 금액을 판매자에게 정산합니다.

- 문서: [기획서](docs/palette_proposal.md) · [정책](docs/palette_policy.md) · [개발 가이드](docs/palette_development_guide.md) · [예외 처리](docs/palette_exception_handling.md) · [서비스 간 계약](docs/palette_shared_contract.md) · ERD(`docs/palette.erd`)

## 주요 기능

| 기능 | 내용 |
| --- | --- |
| 회원·인증 | 이메일 인증 가입, 카카오 로그인, JWT, 판매자 입점 심사, 인플루언서 |
| 상품·검색 | 상품·옵션·재고 관리, 판매 중지 심사, Elasticsearch(nori) 검색, 리뷰, 찜 |
| 장바구니·주문 | 여러 판매자 상품을 한 주문으로, 주문 시 재고 차감(15분 미결제 시 만료), 상품·장바구니 쿠폰, 배송비(3만 원 이상 무료), 반품(1회), 배송 완료 7일 후 자동 구매 확정 |
| 예치금·결제 | 토스페이먼츠·헥토 충전, 지갑 → 홀딩 결제, 결제 재확인, 반품 환불 |
| 배송 | 송장 등록, 택배사 배송 추적(웹훅·폴링), 배송 완료 → 주문에 반영, 수동 완료 |
| 정산 | 구매 확정 시 정산 후보 생성, 매월 10일 Spring Batch 정산, 실패 재시도 |
| 알림 | 주문·배송 이벤트를 SSE로 실시간 알림 |
| AI | 뷰티 설문 기반 맞춤 상품 추천(피하는 성분 제외·임베딩 유사도), 피부가 비슷한 크리에이터 추천, FAQ 근거 챗봇 |
| 관리자 | 판매자 심사·제재, 판매 중지, 반품 승인, 쿠폰, 정산·결제 관리 |

## 아키텍처

- **MSA**: 서비스 8개 + API Gateway. 모든 요청은 Gateway(`:8000`)를 거쳐 서비스로 라우팅됩니다.
- **헥사고날 레이어**: 각 서비스의 도메인 컨텍스트는 `in`(컨트롤러·리스너·스케줄러) / `app`(유스케이스) / `domain`(엔티티·규칙) / `out`(Repository·외부 연동)으로 나뉩니다.
- **서비스 간 통신**: 즉시 응답이 필요한 조회·명령은 내부 HTTP API(`/internal/**`), 상태 변화 알림은 **Transactional Outbox → Kafka** 이벤트로 연결합니다. 컨슈머는 처리 기록으로 중복을 막고, 실패하면 재시도 후 DLT로 보냅니다.
- **DB**: PostgreSQL 하나에 서비스별 스키마·계정을 나눠 씁니다. 다른 서비스의 테이블은 직접 읽지 않습니다.

| 서비스 | 포트 | 역할 |
| --- | --- | --- |
| gateway | 8000 | 라우팅, JWT 검증 |
| member-service | 8081 | 회원·인증·판매자·인플루언서 |
| market-service | 8082 | 상품·검색·장바구니·주문·쿠폰·리뷰 |
| payment-service | 8083 | 예치금 지갑·충전·결제·환불 |
| settlement-service | 8084 | 월 정산(Spring Batch) |
| delivery-service | 8085 | 배송·배송 추적 |
| notification-service | 8086 | SSE 알림 |
| ai-service | 8087 | 설문·맞춤 추천·챗봇 |

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| Language / Build | Java 21, Gradle 9.7.1 (Kotlin DSL, 멀티모듈) |
| Framework | Spring Boot 4.1.1 (Web MVC, Data JPA, Security, Batch, Validation), Spring Cloud Gateway, Spring AI 2.0 |
| Data | PostgreSQL 17 + pgvector, Redis 7.4, Elasticsearch, Flyway |
| Messaging | Apache Kafka 4.1 (Outbox 패턴) |
| 외부 연동 | 토스페이먼츠, 헥토, 카카오 로그인, OpenAI, 배송 추적 API |
| 관측 | Prometheus, Loki, Grafana Alloy, Grafana (Slack 에러 알림) |
| 배포 | Docker Compose, Nginx, GitHub Actions(CI·배포), EC2 |
| 문서·협업 | springdoc-openapi(Swagger UI), GitHub, Jira |

## 프로젝트 구조

```
beadv8_2_TRSS_BE
├── common                 # 모든 서비스 공통: global(보안·예외·Outbox·Kafka) · shared(서비스 간 이벤트·DTO) · standard
├── gateway
├── member-service
├── market-service
├── payment-service
├── settlement-service
├── delivery-service
├── notification-service
├── ai-service
├── src                    # 공통 모듈(Outbox·이벤트) 통합 테스트
├── load-test              # 부하 테스트
└── infra                  # Docker Compose, Nginx, 모니터링, 배포 스크립트
```

```
{service}/src/main/java/com/backend/boundedContext/{context}
├── in       # 컨트롤러, 이벤트 리스너, 스케줄러
├── app      # 유스케이스, Facade
├── domain   # 엔티티, 상태 규칙, ErrorCode
└── out      # Repository, 외부 연동 어댑터
```

## 실행 방법

사전 준비: JDK 21, Docker

```bash
# 1. 인프라 실행 (PostgreSQL+pgvector, Redis, Kafka, Elasticsearch)
docker compose -f infra/compose.yaml up -d

# 2. 환경 변수 파일 만들고 값 채우기 (JWT_SECRET, OPENAI_API_KEY 등. 전체 항목은 .env.example)
cp .env.example .env

# 3. 필요한 서비스 실행 (서비스마다 터미널 하나)
SPRING_PROFILES_ACTIVE=local ./gradlew :gateway:bootRun
SPRING_PROFILES_ACTIVE=local ./gradlew :member-service:bootRun
SPRING_PROFILES_ACTIVE=local ./gradlew :market-service:bootRun
# payment / settlement / delivery / notification / ai-service도 같은 방식
```

- API: http://localhost:8000
- Swagger UI: 서비스별 `http://localhost:{서비스 포트}/swagger-ui/index.html` (예: market은 8082). 서버에서는 `/swagger-ui` 한 화면에서 서비스를 골라 봅니다.
- 테이블은 각 서비스가 시작할 때 Flyway로 만듭니다. 스키마가 꼬이면 `docker compose -f infra/compose.yaml down -v`로 볼륨을 지우고 다시 띄웁니다(**로컬 데이터 삭제**).

### 테스트

```bash
./gradlew test                       # 전체
./gradlew :market-service:test       # 서비스 하나
```

테스트는 로컬 Docker 인프라와 `.env`를 사용합니다.

## API 규칙

- Base URL: `/api/v1` · 인증: `Authorization: Bearer <Access Token>` · 판매자 `/seller/**`, 관리자 `/admin/**`
- 응답은 성공·실패 모두 `RsData { resultCode, msg, data }`이고, HTTP 상태는 `resultCode` 앞자리와 같습니다.

```json
{ "resultCode": "200-1", "msg": "주문이 생성되었습니다.", "data": { "orderId": 123 } }
{ "resultCode": "402-INSUFFICIENT_BALANCE", "msg": "예치금 잔액이 부족합니다.", "data": { "shortage": 18000 } }
```

## 협업 규칙

- 브랜치: `main`(배포) ← `develop`(통합) ← `{type}/TRSS-{이슈번호}-{설명}` (예: `feature/TRSS-8-signup-email-auth`)
- 이슈: GitHub 이슈를 만들면 Jira에 자동 등록됩니다.
- PR: `develop`으로 보내면 CI(GitHub Actions)가 빌드·테스트를 돌립니다.
