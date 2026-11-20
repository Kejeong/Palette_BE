-- 충전 부하 테스트로 만든 충전 건·원장을 지우고 충전용 회원의 지갑 잔액을 0으로 되돌린다 (로컬 DB에서만 실행)
-- 회원과 지갑은 남겨 두고 다음 측정에 다시 씀
-- 결제에 쓰인 충전 건(topup_usage가 참조)은 결제 기록이 깨지지 않게 원장과 함께 남기고, 그 회원의 지갑 잔액도 그대로 둠
-- 사용법: docker exec -i palette-postgres psql -U palette -d palette < load-test/topup/topup-pg-cleanup.sql
BEGIN;

-- 지울 충전 건: 측정 충전 건(payment_key fake-)과 충전용 회원의 충전 건(충전 준비만 된 건 포함) 중 결제에 쓰이지 않은 것
CREATE TEMP TABLE cleanup_topup ON COMMIT DROP AS
SELECT t.id FROM payment.topup t
WHERE (t.payment_key LIKE 'fake-%'
       OR t.member_id IN (SELECT id FROM member.member WHERE email LIKE 'load-topup-user-%@test.com'))
  AND NOT EXISTS (SELECT 1 FROM payment.topup_usage u WHERE u.topup_id = t.id);

DELETE FROM payment.wallet_transaction
WHERE reference_type = 'TOPUP' AND reference_id IN (SELECT id FROM cleanup_topup);

DELETE FROM payment.topup WHERE id IN (SELECT id FROM cleanup_topup);

-- 남은 충전 건(결제에 쓰인 건)이 없는 충전용 회원만 잔액 0
UPDATE payment.wallet w SET balance = 0
WHERE w.member_id IN (SELECT id FROM member.member WHERE email LIKE 'load-topup-user-%@test.com')
  AND NOT EXISTS (SELECT 1 FROM payment.topup t WHERE t.member_id = w.member_id);

-- 남긴 충전 건 (결제에 쓰여 지우지 않은 것)
SELECT t.member_id, count(*) AS kept_topups
FROM payment.topup t
WHERE t.payment_key LIKE 'fake-%'
   OR t.member_id IN (SELECT id FROM member.member WHERE email LIKE 'load-topup-user-%@test.com')
GROUP BY t.member_id
ORDER BY t.member_id;

COMMIT;
