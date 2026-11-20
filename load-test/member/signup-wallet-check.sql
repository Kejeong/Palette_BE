-- 회원가입 부하 테스트 결과 확인 (RUN_ID별)
-- 회원은 member 스키마, 지갑은 payment 스키마에 있다
-- 사용법: docker exec -i palette-postgres psql -U palette -d palette -v run_id="'before-normal'" < load-test/member/signup-wallet-check.sql

-- 이번 실행으로 만들어진 회원 수와, 그중 지갑이 없는 회원 수 (유실)
SELECT count(*)                                   AS members,
       count(*) FILTER (WHERE w.id IS NULL)       AS members_without_wallet
FROM member.member m
LEFT JOIN payment.wallet w ON w.member_id = m.id
WHERE m.email LIKE 'load-' || :run_id || '-%@test.com';

-- 지갑이 두 개 이상인 회원 (중복 생성)
SELECT m.id, count(w.id) AS wallets
FROM member.member m
JOIN payment.wallet w ON w.member_id = m.id
WHERE m.email LIKE 'load-' || :run_id || '-%@test.com'
GROUP BY m.id
HAVING count(w.id) > 1;
