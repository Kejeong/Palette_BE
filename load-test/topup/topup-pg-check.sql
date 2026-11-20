-- 충전 부하 테스트 결과 확인 (RUN_ID별). 충전 건·원장은 payment 스키마에 있다
-- 사용법: docker exec -i palette-postgres psql -U palette -d palette -v run_id="'before-normal'" < load-test/topup/topup-pg-check.sql

-- 이번 실행의 충전 건 상태별 건수 (PROCESSING이 남아 있으면 대사 스케줄러가 아직 확정하지 않은 것)
SELECT status, count(*) AS topups
FROM payment.topup
WHERE payment_key LIKE 'fake-' || :run_id || '-%'
GROUP BY status
ORDER BY status;

-- 이번 실행의 PG별·상태별 건수 (애프터: 충전 준비가 고른 PG)
SELECT pg_provider, status, count(*) AS topups
FROM payment.topup
WHERE payment_key LIKE 'fake-' || :run_id || '-%'
GROUP BY pg_provider, status
ORDER BY pg_provider, status;

-- PG 우회 시점: PG별 첫·마지막 충전 준비 시각 (첫 HECTO의 since_first = 첫 충전부터 우회까지)
SELECT pg_provider, count(*) AS topups, min(created_at) AS first_created, max(created_at) AS last_created,
       min(created_at) - (SELECT min(created_at) FROM payment.topup WHERE payment_key LIKE 'fake-' || :run_id || '-%') AS since_first
FROM payment.topup
WHERE payment_key LIKE 'fake-' || :run_id || '-%'
GROUP BY pg_provider
ORDER BY pg_provider;

-- 원장 CHARGE가 두 번 이상 기록된 충전 건 (중복 반영) → 0행이어야 함
SELECT t.id AS topup_id, count(wt.id) AS charges
FROM payment.topup t
JOIN payment.wallet_transaction wt ON wt.reference_type = 'TOPUP' AND wt.reference_id = t.id AND wt.type = 'CHARGE'
WHERE t.payment_key LIKE 'fake-' || :run_id || '-%'
GROUP BY t.id
HAVING count(wt.id) > 1;
