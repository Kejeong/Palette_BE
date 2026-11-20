#!/usr/bin/env bash
# 애프터 측정용: 지갑이 없는 회원(유실)이 0건이 될 때까지 DB를 확인하며 시각을 찍는다.
# 컨슈머를 다시 켠 직후 실행하고, 서버 로그의 "partitions assigned" 시각과 비교해 복구 시간을 구한다.
#
# 사용법: load-test/member/wait-wallet-recovery.sh RUN_ID [INTERVAL_SEC] [TIMEOUT_SEC]
set -euo pipefail

RUN_ID="${1:?RUN_ID를 지정해 주세요}"
INTERVAL="${2:-0.5}"
TIMEOUT="${3:-600}"
PG_CONTAINER="${PG_CONTAINER:-palette-postgres}"

now() { python3 -c 'import datetime; print(datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3])'; }
epoch() { python3 -c 'import time; print(time.time())'; }

query="SELECT count(*) FILTER (WHERE w.id IS NULL)
       FROM member.member m LEFT JOIN payment.wallet w ON w.member_id = m.id
       WHERE m.email LIKE 'load-${RUN_ID}-%@test.com';"

start=$(epoch)
echo "$(now) 확인 시작 (RUN_ID=${RUN_ID})"
while :; do
  missing=$(docker exec "$PG_CONTAINER" psql -U palette -d palette -tAc "$query")
  echo "$(now) 유실 ${missing}건"
  if [ "$missing" -eq 0 ]; then
    end=$(epoch)
    python3 -c "print(f'유실 0건 도달: 확인 시작부터 {${end} - ${start}:.2f}초')"
    break
  fi
  if python3 -c "import sys; sys.exit(0 if $(epoch) - ${start} > ${TIMEOUT} else 1)"; then
    echo "$(now) ${TIMEOUT}초 안에 유실이 0건이 되지 않음"
    exit 1
  fi
  sleep "$INTERVAL"
done
