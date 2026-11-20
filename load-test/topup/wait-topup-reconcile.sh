#!/usr/bin/env bash
# 장애 측정 후 PROCESSING(결과 모름) 충전 건이 0건이 될 때까지 DB를 확인하며 시각을 찍는다.
# 가짜 PG를 normal로 되돌린 직후 실행한다. 대사 스케줄러가 PG 조회로 확정하는 데 걸린 시간을 잰다.
#
# 사용법: load-test/topup/wait-topup-reconcile.sh RUN_ID [INTERVAL_SEC] [TIMEOUT_SEC]
set -euo pipefail

RUN_ID="${1:?RUN_ID를 지정해 주세요}"
INTERVAL="${2:-1}"
TIMEOUT="${3:-900}"
PG_CONTAINER="${PG_CONTAINER:-palette-postgres}"

now() { python3 -c 'import datetime; print(datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3])'; }
epoch() { python3 -c 'import time; print(time.time())'; }

query="SELECT count(*) FILTER (WHERE status = 'PROCESSING') || ' ' || count(*) FILTER (WHERE status = 'DONE')
       FROM payment.topup WHERE payment_key LIKE 'fake-${RUN_ID}-%';"

start=$(epoch)
echo "$(now) 확인 시작 (RUN_ID=${RUN_ID})"
while :; do
  read -r processing done_count <<< "$(docker exec "$PG_CONTAINER" psql -U palette -d palette -tAc "$query")"
  echo "$(now) PROCESSING ${processing}건 / DONE ${done_count}건"
  if [ "$processing" -eq 0 ]; then
    end=$(epoch)
    python3 -c "print(f'PROCESSING 0건 도달: 확인 시작부터 {${end} - ${start}:.2f}초')"
    break
  fi
  if python3 -c "import sys; sys.exit(0 if $(epoch) - ${start} > ${TIMEOUT} else 1)"; then
    echo "$(now) ${TIMEOUT}초 안에 PROCESSING이 0건이 되지 않음"
    exit 1
  fi
  sleep "$INTERVAL"
done
