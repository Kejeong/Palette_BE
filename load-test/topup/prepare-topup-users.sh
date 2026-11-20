#!/usr/bin/env bash
# 충전 부하 테스트에 쓸 회원을 가입시킨다 (처음 한 번만).
# 이메일 load-{USER_RUN_ID}-{i}@test.com, 비밀번호 Load1234! — 지갑은 가입 이벤트로 결제가 만든다
#
# 사용법: load-test/topup/prepare-topup-users.sh [USERS] [USER_RUN_ID]   (기본 10, topup-user)
set -euo pipefail

USERS="${1:-10}"
USER_RUN_ID="${2:-topup-user}"
MEMBER_URL="${MEMBER_URL:-http://localhost:8081}"
DIR="$(cd "$(dirname "$0")" && pwd)"

"$DIR/../member/prepare-signup-tokens.sh" "$USER_RUN_ID" "$USERS"

for ((i = 0; i < USERS; i++)); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$MEMBER_URL/api/v1/auth/signup" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"load-${USER_RUN_ID}-${i}@test.com\",\"password\":\"Load1234!\",\"name\":\"충전부하\",\"verificationToken\":\"load-${USER_RUN_ID}-${i}\"}")
  echo "회원 ${i}: HTTP ${code} (409면 이미 가입됨)"
done
