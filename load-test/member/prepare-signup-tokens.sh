#!/usr/bin/env bash
# 회원가입 부하 테스트용 이메일 인증 완료 토큰을 로컬 Redis에 미리 넣는다.
# 토큰 load-{RUN_ID}-{i} → 이메일 load-{RUN_ID}-{i}@test.com (1시간 뒤 만료)
#
# 사용법: load-test/member/prepare-signup-tokens.sh RUN_ID COUNT
#   RUN_ID, COUNT는 k6 실행 때와 같은 값을 쓴다
set -euo pipefail

RUN_ID="${1:?RUN_ID를 지정해 주세요}"
COUNT="${2:?COUNT를 지정해 주세요}"
REDIS_CONTAINER="${REDIS_CONTAINER:-palette-redis}"

for ((i = 0; i < COUNT; i++)); do
  echo "SET member:email-verified:load-${RUN_ID}-${i} load-${RUN_ID}-${i}@test.com EX 3600"
done | docker exec -i "$REDIS_CONTAINER" redis-cli > /dev/null

echo "토큰 ${COUNT}개 준비 완료 (RUN_ID=${RUN_ID})"
