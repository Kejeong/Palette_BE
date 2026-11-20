-- 회원가입 부하 테스트로 만든 회원·지갑을 모두 지운다 (로컬 DB에서만 실행)
-- 사용법: docker exec -i palette-postgres psql -U palette -d palette < load-test/member/signup-wallet-cleanup.sql
BEGIN;

-- outbox 도입 후에는 가입마다 MemberSignedUpEvent 행도 남는다 (aggregate_id = 회원 ID)
DELETE FROM member.outbox_event
WHERE event_type = 'MemberSignedUpEvent'
  AND aggregate_id IN (SELECT id FROM member.member WHERE email LIKE 'load-%@test.com');

DELETE FROM payment.wallet
WHERE member_id IN (SELECT id FROM member.member WHERE email LIKE 'load-%@test.com');

DELETE FROM member.member
WHERE email LIKE 'load-%@test.com';

COMMIT;
