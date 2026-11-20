# 장애 측정 도구

회원·충전·알림 서비스에 장애를 일부러 일으켜, 장애 대응을 넣기 전(비포)과 넣은 뒤(애프터)를 같은 조건으로 비교한 도구입니다.

> **로컬에서만 실행합니다.** 개발 서버·운영에서는 실행하지 않습니다. 측정이 서버 전체에 영향을 주기 때문입니다.
> - **다른 사람의 요청까지 실패합니다.** 서비스를 내리거나 `wallet` 테이블 이름을 바꾸거나 잠그면, 그 서버를 쓰는 모든 요청이 같이 실패합니다.
> - **설정을 바꿔 다시 띄워야 합니다.** PG 주소를 가짜 PG로, 대사 간격을 1분으로 바꾸고, 실행마다 서비스를 재시작해 서킷 브레이커를 초기화합니다.
> - **다른 사람의 데이터를 바꾸거나 지울 수 있습니다.** 주문 준비 스크립트는 판매 중인 모든 상품 옵션의 재고를 10,000으로 올리고 구매자 잔액도 올립니다. 정리 SQL은 측정이 만든 행을 기억하지 않고 형식(`load-%@test.com` 회원, `fake-%` 결제키)으로 골라 지우므로, 같은 형식의 다른 데이터도 함께 지워집니다.
> - **실제 PG로는 장애를 만들 수 없습니다.** 진짜 토스·헥토는 일부러 오류를 내게 하거나 멈출 수 없어 가짜 PG 서버를 씁니다.

| 측정 | 장애 | 폴더 |
| --- | --- | --- |
| [회원가입–지갑 생성](#1-회원가입지갑-생성) | 지갑 생성(결제)이 실패·지연되거나 결제 서비스가 꺼짐 | `member/` |
| [충전 PG 장애](#2-충전-pg-장애) | 토스(주 PG)·헥토(두 번째 PG)가 오류를 내거나 응답하지 않음 | `topup/` |
| [알림 서비스 장애](#3-알림-서비스-장애) | 알림 서비스가 꺼짐, 같은 이벤트를 다시 받음 | `notification/` |

## 공통

### 준비

- k6: `brew install k6`
- 인프라(Postgres·Redis·Kafka): `docker compose -f infra/compose.yaml up -d`
  - 스크립트는 컨테이너 이름 `palette-postgres`·`palette-redis`·`palette-kafka`를 씁니다. 다르면 `PG_CONTAINER`·`REDIS_CONTAINER`·`KAFKA_CONTAINER` 환경 변수로 바꿉니다.
- 서비스는 local 프로필로 실행합니다. 포트: member 8081, market 8082, payment 8083, delivery 8085, notification 8086
- 결과 폴더: `mkdir -p load-test/results/{member,topup,notification}`
  - k6 요약 JSON·HTML 리포트·서버 로그·주문 목록이 쌓입니다. `.gitignore`로 커밋하지 않습니다.

### 같은 조건으로 비교하기

- 모든 측정은 **요청 200건, 동시 사용자(VU) 10**으로 맞춥니다. 장애를 만드는 방법만 구조에 맞게 바꿉니다.
- 실행마다 `RUN_ID`를 다르게 줍니다. 이 값이 측정 데이터 이름에 붙어서(가입 이메일 `load-{RUN_ID}-{번호}@test.com`, 충전 결제키 `fake-{RUN_ID}-{번호}`, 알림 주문 목록 `orders-{RUN_ID}.csv`), 확인 SQL과 가짜 PG가 이번 실행 데이터만 골라 셉니다. 같은 값을 다시 쓰면 이메일·결제키가 겹쳐 가입·승인이 거절되고 이전 결과가 섞입니다.
- 측정 회원은 `load-*@test.com`, 비밀번호 `Load1234!`입니다. 이메일 인증은 Redis에 인증 완료 토큰을 미리 넣어 건너뜁니다.

### 측정 환경의 한계

- 한 대의 노트북에서 서비스·DB·Kafka·k6를 함께 돌렸습니다. **응답 시간은 참고값**이고, 비교의 중심은 성공률·결과 대기·유실·중복입니다.
- 비포와 애프터는 다른 날 잰 것이 있어 응답 시간 차이에는 측정 환경 차이가 섞여 있습니다.
- 운영 수준의 응답 시간이 필요하면 운영과 같은 사양의 별도 환경에서 같은 스크립트로 다시 재야 합니다.

## 1. 회원가입–지갑 생성

지갑은 가입한 뒤 결제 서비스가 만듭니다. 결제 쪽이 실패하거나 느리거나 꺼져 있을 때 가입이 어떻게 되는지 잽니다.

| 시점 | 구조 | 장애를 만드는 방법 |
| --- | --- | --- |
| 비포 | 모놀리스. 가입 트랜잭션 안에서 지갑 생성 | `wallet` 테이블 이름 변경(에러형), 테이블 잠금(지연형) |
| 애프터 1 | 모놀리스 + outbox·Kafka | 지갑 생성 컨슈머만 끄고 실행 |
| 애프터 2 | 회원 서비스 분리 | 결제가 들어 있는 프로세스를 통째로 내림 |

> 비포·애프터 1은 모놀리스 시절 구조라 지금 코드로는 다시 잴 수 없습니다. 스크립트는 지금 구조(회원 `member` 스키마, 지갑 `payment` 스키마, 가입 8081)에 맞춰 두었고, 지금은 애프터 2 방식으로 잴 수 있습니다.

### 파일

| 파일 | 하는 일 |
| --- | --- |
| `signup-wallet-failure.js` | k6: 회원가입 `COUNT`건 |
| `prepare-signup-tokens.sh` | Redis에 이메일 인증 완료 토큰을 넣음 (1시간 뒤 만료, k6 바로 앞에 실행) |
| `signup-wallet-check.sql` | 이번 실행의 회원 수, 지갑 없는 회원(유실), 지갑 두 개 이상(중복) |
| `wait-wallet-recovery.sh` | 유실이 0건이 될 때까지 0.5초마다 확인하며 시각을 찍음 |
| `signup-wallet-cleanup.sql` | `load-%@test.com` 회원·지갑·가입 이벤트 outbox 행 삭제 |

### 지표

| 지표 | 확인 방법 |
| --- | --- |
| 가입 성공률 | k6 `signup_success` (HTTP 200이고 `resultCode`가 `200-1`) |
| 응답 시간 p95 | 전체 `http_req_duration`, 성공만 `signup_success_duration` (실패는 빨리 끝나 전체 p95를 낮춰 보이게 함) |
| 유실·중복 | `signup-wallet-check.sql` |
| 복구 시간 | 서버 로그의 `payment` 그룹 `partitions assigned` 시각 → `wait-wallet-recovery.sh`의 "유실 0건" 시각 |

### 실행 (애프터 2: 결제 서비스 중지)

1. member-service(8081)와 payment-service(8083)를 띄움
2. 기준선

   ```bash
   load-test/member/prepare-signup-tokens.sh after2-normal 200
   k6 run -e RUN_ID=after2-normal -e COUNT=200 -e VUS=10 --summary-export=load-test/results/member/after2-normal.json load-test/member/signup-wallet-failure.js
   load-test/member/wait-wallet-recovery.sh after2-normal
   docker exec -i palette-postgres psql -U palette -d palette -v run_id="'after2-normal'" < load-test/member/signup-wallet-check.sql
   ```

3. payment-service를 끄고 같은 명령으로 가입 (`RUN_ID=after2-payment-down`). 장애 중에는 회원 200, 유실 200, `payment` 그룹 lag 200이 정상입니다.

   ```bash
   docker exec palette-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group payment
   ```

4. **복구 확인 스크립트를 먼저 켠 뒤** payment-service를 다시 띄움 (지갑 200개가 몇 초 만에 생겨서, 나중에 켜면 끝 시각을 재지 못함)

   ```bash
   load-test/member/wait-wallet-recovery.sh after2-payment-down
   ```

5. 서버 로그에서 `partitions assigned` 시각을 찾아 복구 시간을 계산하고, `signup-wallet-check.sql`로 중복 0건 확인
6. 정리 (기록을 다 남긴 뒤): `docker exec -i palette-postgres psql -U palette -d palette < load-test/member/signup-wallet-cleanup.sql`

비포·애프터 1의 장애 주입 방법 (참고)
- 에러형: `ALTER TABLE wallet RENAME TO wallet_down;` 후 가입, 끝나면 반드시 되돌림
- 지연형: 다른 세션에서 `BEGIN; LOCK TABLE wallet IN ACCESS EXCLUSIVE MODE;`를 열어 두고 가입, 끝나면 `ROLLBACK;`
- 애프터 1 컨슈머 중지: `SPRING_KAFKA_LISTENER_AUTO_STARTUP=false`로 서버 실행 → 가입 → 리스너를 켜고 재시작

### 결과

| 실행 | 일시 · 커밋 | 성공률 | p95 | 유실 | 중복 | 복구 시간 |
| --- | --- | --- | --- | --- | --- | --- |
| before-normal | 10/30 · `6266033` | 100% | 158.61ms | 0 | 0 | — |
| before-error | 10/30 · `6266033` | **0%** (가입도 롤백) | 293.21ms | 0 | 0 | — |
| before-lock | 10/30 · `6266033` | **0%** (190건 실패, 10건은 잠금 해제 후 커밋) | 46.53s | 0 | 0 | — |
| after1-normal | 11/04 · `b707ce9` | 100% | 267.11ms | 0 | 0 | — |
| after1-consumer-stop | 11/04 · `b707ce9` | **100%** | 396.84ms | 장애 중 200 → 0 | 0 | **2.20s** |
| after1-error (COUNT=20) | 11/04 · `b707ce9` | **100%** | 578.99ms | 20 (모두 DLT) → 수동 재전송 후 0 | 0 | 재전송 후 1.07s |
| after2-normal | 11/09 · `c5408f7` | 100% | 210.24ms | 0 | 0 | — |
| after2-payment-down | 11/09 · `c5408f7` | **100%** | 230.57ms | 장애 중 200 → 0 | 0 | **1.89s** |

- 지갑 쪽 장애에서 가입 성공률 0%(비포) → 100%(애프터 1·2), 컨슈머가 붙은 뒤 약 2초 안에 유실·중복 0건
- 한계: 지갑 생성 재시도(약 15초)를 넘는 에러형 장애는 메시지가 DLT로 가서 사람이 재전송해야 복구됨 (after1-error)
- 애프터 1의 p95가 비포보다 높은 것은 모놀리스 하나에서 relay·컨슈머·가입이 같은 커넥션 풀을 나눠 쓴 차이로 봄

## 2. 충전 PG 장애

진짜 토스·헥토는 일부러 죽일 수 없어서, 같은 API를 흉내 내는 가짜 PG 서버를 띄우고 모드를 바꿔 장애를 만듭니다.

| 시점 | 구조 | 장애 |
| --- | --- | --- |
| 비포 | 토스 하나, 서킷 브레이커 없음 | 가짜 토스를 error·timeout으로 |
| 애프터 | PG 포트 + 헥토 + 서킷 브레이커(충전 준비 때 PG 자동 선택) | 같은 방법으로 토스만 장애, 둘 다 장애, 둘 다 멈춤 |

### 파일

| 파일 | 하는 일 |
| --- | --- |
| `fake-pg/fake_toss.py` | 가짜 토스 (9090). 승인·결제키 조회·주문번호 조회 |
| `fake-pg/fake_hecto.py` | 가짜 헥토 (9095). 실제 헥토와 같은 경로·형식으로 승인·조회·취소, 금액 AES-256-ECB(`openssl` 명령)·SHA-256 해시 |
| `topup-pg-failure.js` | k6: 측정 회원 로그인(한 번) → 충전 준비 → 승인 |
| `prepare-topup-users.sh` | 충전용 회원 10명 가입 (처음 한 번) |
| `topup-pg-check.sql` | 이번 실행의 상태별·PG별 건수, PG 우회 시점, 원장 중복 반영 |
| `wait-topup-reconcile.sh` | PROCESSING이 0건이 될 때까지 시각을 찍음 (대사 확정 시간) |
| `topup-pg-cleanup.sql` | 측정 충전 건·원장 삭제, 충전 회원 잔액 0 (결제에 쓰인 충전 건과 그 회원 잔액은 남김) |

가짜 PG 모드 (두 서버 같음)

| 모드 | 동작 |
| --- | --- |
| `normal` | 바로 승인 |
| `error` | 500으로 응답, 승인하지 않음 |
| `timeout` | 승인은 해 두고 35초 동안 응답하지 않음 (돈은 빠졌는데 결과를 모름) |
| `hang` | `timeout`에 더해 조회·취소도 35초 동안 응답하지 않음 (PG가 멈춤) |

```bash
curl -X POST 'localhost:9090/__mode?value=error'          # 모드 변경 (헥토는 9095)
curl 'localhost:9090/__stats?prefix=fake-after-error-3-'  # 이번 실행의 승인 수, 요청 수(누적)
curl -X POST localhost:9090/__reset                       # 승인 기록 비우기
```

가짜 PG와 실제 PG의 차이
- 승인 기록이 없는 결제를 조회하면 바로 "결제 없음"을 줍니다. 실제 토스는 결제창 인증 후 약 30분 동안 `IN_PROGRESS`로 조회되므로, 오류형의 실패 확정은 실제로는 더 오래 걸립니다.
- 가짜 헥토는 연결을 끊지 않습니다. 실제 헥토 테스트베드는 새 연결의 약 1/3을 끊었습니다.

### 지표

| 지표 | 확인 방법 |
| --- | --- |
| 충전 완료율 · 결과 대기율 · 실패율 | k6 `topup_done`·`topup_processing`·`topup_failed` (승인 응답 `data.status`) |
| PG 선택 | k6 `topup_pg_toss`·`topup_pg_hecto`·`topup_pg_unavailable`(충전 준비 503) |
| 승인 응답 p95 | `http_req_duration{name:confirm}`, 완료만 `topup_done_duration` |
| 미반영 | 가짜 PG `__stats`의 `approved` − DB의 DONE (돈은 빠졌는데 예치금 0). 대사 후 0이어야 함 |
| 중복 반영 | 원장 CHARGE가 두 번 이상인 충전 건 (`topup-pg-check.sql`). 0이어야 함 |
| 대사 확정 시간 | 가짜 PG를 normal로 되돌린 뒤 PROCESSING 0건까지 (`wait-topup-reconcile.sh`) |

### 준비 (처음 한 번)

1. 인프라와 member-service(8081)를 띄움. **IntelliJ 등으로 띄운 payment-service는 끕니다.** 두 개가 같이 돌면 원래 payment의 대사가 측정 건을 실제 PG로 조회합니다.
2. 가짜 PG 두 개를 각각 다른 터미널에서 실행

   ```bash
   python3 -I load-test/topup/fake-pg/fake_toss.py
   python3 -I load-test/topup/fake-pg/fake_hecto.py
   ```

3. payment-service를 가짜 PG로 연결해 실행. 대사 대기를 5분 → 1분, 간격을 300초 → 30초로 줄여 측정 시간을 줄입니다. 헥토 키는 가짜 헥토와 같은 측정용 값입니다.

   ```bash
   ./gradlew :payment-service:bootRun --args='--custom.toss.baseUrl=http://localhost:9090 --custom.hecto.apiBaseUrl=http://localhost:9095 --custom.hecto.mchtId=fakemcht --custom.hecto.aesKey=fake-hecto-aes-key-32-bytes-0000 --custom.hecto.hashKey=fake-hecto-hash-key --custom.payment.topupReconcileDelayMinutes=1 --custom.payment.topupReconcileIntervalSeconds=30'
   ```

4. 충전용 회원 10명 가입 후 지갑이 생겼는지 확인

   ```bash
   load-test/topup/prepare-topup-users.sh 10
   docker exec palette-postgres psql -U palette -d palette -c "SELECT m.email, w.balance FROM member.member m LEFT JOIN payment.wallet w ON w.member_id = m.id WHERE m.email LIKE 'load-topup-user-%@test.com' ORDER BY m.email;"
   ```

### 실행마다 반복할 것

1. payment-service를 껐다 다시 띄움. 서킷 상태가 메모리에만 있어서, 재시작해야 앞 실행에서 열린 서킷이 닫힙니다.
2. 가짜 PG 둘 다 `normal`로 돌림
3. 예열 20건 (서버를 띄운 직후 첫 요청이 느린 것을 피함). `RUN_ID`는 실행마다 새 이름을 씁니다.

   ```bash
   k6 run -q -e RUN_ID=warmup-1 -e COUNT=20 -e VUS=2 load-test/topup/topup-pg-failure.js
   ```

4. 장애를 주입하고 k6 실행 (HTML 리포트가 필요 없으면 `K6_WEB_DASHBOARD*`는 빼도 됨)

   ```bash
   curl -X POST 'localhost:9090/__mode?value=error'
   K6_WEB_DASHBOARD=true K6_WEB_DASHBOARD_PERIOD=100ms K6_WEB_DASHBOARD_EXPORT=load-test/results/topup/after-error.html \
     k6 run -e RUN_ID=after-error -e COUNT=200 -e VUS=10 --summary-export=load-test/results/topup/after-error.json load-test/topup/topup-pg-failure.js
   ```

5. 결과 대기가 남는 실행(오류형·둘 다 장애·멈춤형)은 **약 100초 기다린 뒤** 복구합니다. 기다리지 않으면 대사 대기 1분이 확정 시간에 섞여 비포와 비교할 수 없습니다.

   ```bash
   curl -X POST 'localhost:9090/__mode?value=normal'
   load-test/topup/wait-topup-reconcile.sh after-error
   ```

6. DB와 가짜 PG 확인

   ```bash
   docker exec -i palette-postgres psql -U palette -d palette -v run_id="'after-error'" < load-test/topup/topup-pg-check.sql
   curl 'localhost:9090/__stats?prefix=fake-after-error-'
   curl 'localhost:9095/__stats?prefix=fake-after-error-'
   ```

7. 끝나면 정리

   ```bash
   docker exec -i palette-postgres psql -U palette -d palette < load-test/topup/topup-pg-cleanup.sql
   curl -X POST localhost:9090/__reset
   curl -X POST localhost:9095/__reset
   ```

### 시나리오

| 시나리오 | 가짜 토스 | 가짜 헥토 | 비고 |
| --- | --- | --- | --- |
| ① 기준선 | normal | normal | 반드시 먼저 잼 |
| ② 오류형 | error | normal | 토스만 500 |
| ③ 응답 유실형 | timeout | normal | 돈은 빠졌는데 결과를 모르는 가장 위험한 상황 |
| ④ 둘 다 장애 | error | error | 두 번째 PG가 없을 때와 같은 상황 (우회 효과만 보는 대조군) |
| ⑤ 멈춤형 | hang | hang | 장애 중 대사 한 회차 시간을 봄 |
| ⑥ 멈춤형 대조군 | hang | hang | ⑤와 같은 장애에 고치기 전 설정 (아래) |

⑤·⑥은 기다리는 동안 payment 로그에서 대사 한 회차 시간을 잽니다.
- ⑤: `PG 결제 조회 결과를 알 수 없어` WARN 3줄 뒤 `PROCESSING 충전 건 결과 확인: … 확인 대기 3건` 한 줄. 한 회차 = 요약 줄 시각 − (첫 WARN 시각 − 5초)
- ⑥: 고치기 전 커밋으로 돌아가지 않고 설정만 바꿉니다. 조회 응답 대기 30초, 회차를 끝까지 처리하도록 연속 건수를 크게 둡니다. 승인 한 건이 약 60초 걸려 k6 응답 대기를 90초로, 요청은 20건으로 줄입니다. 200건이면 서킷이 OPEN → HALF_OPEN을 오가며 503 없이 결과 대기만 쌓입니다.

  ```bash
  ./gradlew :payment-service:bootRun --args='... (준비 3번과 같음) --custom.payment.topupReconcileUnknownStreak=1000 --custom.toss.lookupReadTimeoutSeconds=30 --custom.hecto.lookupReadTimeoutSeconds=30'
  k6 run -e RUN_ID=after-stall-control -e COUNT=20 -e VUS=10 -e TIMEOUT=90s --summary-export=load-test/results/topup/after-stall-control.json load-test/topup/topup-pg-failure.js
  ```

비포를 다시 재려면 비포 커밋(`7ba1b7d`)에서 가짜 토스만 띄우고 `--custom.toss.baseUrl`과 대사 설정만 넘깁니다.

### 결과

| 장애 | 비포 (11/10~11, `7ba1b7d`) | 애프터 (11/19, `fbabf5b`) |
| --- | --- | --- |
| ① 기준선 | 완료 100% | 완료 100%, 미반영·중복 0 |
| ② 토스 500 | 완료 0%, 결과 대기 100% → 대사 후 실패 200 | **완료 95%** (헥토 190), 결과 대기 10건 → 대사 후 실패 10, 우회 0.05초 |
| ③ 토스 응답 유실 | 완료 0%, **30초 뒤 500 (실패 100%)**, p95 30.21s | **완료 100%**, p95 1.58s (토스 10건은 그 자리에서 조회해 DONE, 나머지 헥토) |
| ④ 둘 다 장애 | — | 완료 0%, 결과 대기 20건, 나머지 180건은 바로 503 (충전 건 만들지 않음) |

멈춤형 (결과 대기 20건)

| | ⑥ 고치기 전 설정 | ⑤ 고친 뒤 설정 |
| --- | --- | --- |
| 장애 중 대사 한 회차 | 약 5분 (10건 × 30초, 대상이 늘면 최대 50분) | **약 15초** (결과 모름 3건 × 5초) |
| 복구 후 확정 | 51.85초 | 30.57초 |
| 미반영 · 중복 | 0 · 0 | 0 · 0 |

- 주 PG 장애 시 충전 완료율 0% → 95~100%, 모든 실행에서 미반영 0·중복 0
- 비포에서 발견한 것: 응답을 못 받으면 "결과 확인 중(200 + PROCESSING)"으로 답해야 하는데 실제로는 500이었음. 응답 읽기 단계의 시간 초과가 처리하지 않던 예외로 올라왔고, 테스트는 목으로 시간 초과를 만들어 통과하고 있었음 (#317에서 수정)
- 처음 동시 요청 10건이 토스로 가는 것은 VU 10개가 같은 순간에 시작해서입니다. 실제로는 장애 시작 때 처리 중이던 요청 수만큼 토스로 갑니다.

## 3. 알림 서비스 장애

알림 서비스가 꺼져 있어도 주문 결제·배송이 정상인지, 다시 켜면 알림이 유실·중복 없이 따라오는지 잽니다. 알림은 주문·배송 요청 경로에 없고 outbox → Kafka(`order.events`·`delivery.events`)로만 이어져 있습니다.

### 파일

| 파일 | 하는 일 |
| --- | --- |
| `service.sh` | delivery·notification을 `bootRun`으로 켜고 끔. 시각을 찍고 서버 로그를 남김 (외부 배송 조회는 가짜 값으로 끔) |
| `prepare-orders.py` | 구매자 잔액·재고를 올리고 결제 전 주문 `COUNT`건 준비 (주문은 15분 뒤 만료라 측정 직전 실행) |
| `run.sh` · `notification-failure.js` | k6로 결제 → 배송 생성 대기 → 배송 시작 → 배송 완료를 차례로 실행 |
| `check.sh` | 이번 실행 주문 기준 주문·배송 상태, 이벤트 발행, 유형별 알림 수, 유실·중복 |
| `wait-recovery.sh` | 다시 켠 뒤 유실 0이 될 때까지 시각과 `notification` 그룹 lag를 찍음 |

### 준비

- member·market·payment를 local 프로필로 띄움. delivery·notification은 `service.sh`로 띄움
- 구매자는 충전 측정 회원(`load-topup-user-0..9`)을 씁니다. 없으면 `load-test/topup/prepare-topup-users.sh 10`
- 물류·관리자는 회원 ID 헤더로 delivery를 직접 부릅니다 (`LOGISTICS_ID` 기본 294, `ADMIN_ID` 기본 1. 로컬 시드 값이 다르면 환경 변수로 바꿈)
- 주문은 3만 원 이상(무료 배송)으로 만듭니다. 배송비가 있으면 결제 때 플랫폼 지갑이 필요한데 로컬에는 없습니다.

### 실행

```bash
load-test/notification/service.sh start delivery
load-test/notification/service.sh start notification

# ① 기준선
load-test/notification/prepare-orders.py baseline 200
load-test/notification/run.sh baseline
load-test/notification/check.sh baseline

# ② 장애: 알림을 끄고 같은 흐름 실행 → 알림 0건, lag 600이 정상
load-test/notification/service.sh stop notification
load-test/notification/prepare-orders.py outage 200
load-test/notification/run.sh outage

# ③ 복구: 다시 켜고 바로 유실 0까지 확인
load-test/notification/service.sh start notification recovery
load-test/notification/wait-recovery.sh outage
load-test/notification/check.sh outage
```

④ 재전달: notification을 끄고 그룹 offset을 ② 직전 시각으로 되돌린 뒤 다시 켭니다. 같은 이벤트를 다시 받아도 알림 수가 그대로인지 봅니다. 아래 시각은 측정 때 쓴 값이라 실행할 때는 ② 직전 시각으로 바꿉니다.

```bash
docker exec palette-kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group notification --reset-offsets \
  --topic order.events --topic delivery.events --to-datetime 2026-11-19T12:42:50.000+09:00 --execute
```

### 결과 (11/19, `fbabf5b`, 주문 200건)

| API | ① 기준선 (알림 켬) | ② 장애 (알림 끔) |
| --- | --- | --- |
| 주문 결제 | 100% · p95 143.2ms | 100% · p95 86.0ms |
| 배송 시작 | 100% · p95 58.4ms | 100% · p95 37.6ms |
| 배송 완료 | 100% · p95 25.8ms | 100% · p95 31.6ms |

| 시점 | 이벤트 | 알림 | 유실 | 중복 |
| --- | --- | --- | --- | --- |
| ① 기준선 | 600 | 600 | 0 | 0 |
| ② 장애 중 | 600 (Kafka에 남음, lag 600) | 0 | 600 | 0 |
| ③ 복구 후 | 600 | 600 | **0** | **0** |
| ④ 재전달 후 | 600 다시 읽음 | 600 그대로 | 0 | **0** |

- 알림이 꺼져도 결제·배송은 100% 성공하고 응답 시간도 나빠지지 않음 (②가 조금 빠른 것은 ①이 첫 실행이라 JVM 예열 차이로 봄)
- 다시 켜면 파티션 할당부터 약 5.5초 안에 밀린 600건을 모두 처리
- 발견: 밀린 이벤트를 한꺼번에 처리할 때 배송 알림 일부가 주문 완료 알림보다 먼저 저장돼 상품명이 비었음 (시작 44건, 완료 25건) → #473에서 보완
- 한계: 단일 브로커·단일 인스턴스, 정상 종료(SIGTERM)로 끔. 처리 도중 강제 종료는 ④ 재전달로 대신 확인함
