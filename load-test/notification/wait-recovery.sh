#!/usr/bin/env bash
# 알림 장애 측정 복구 확인: notification을 다시 켠 직후 실행해 유실(처리 안 된 이벤트)이 0이 될 때까지 시각·lag를 찍는다.
# 서버 로그의 "partitions assigned" 시각과 비교해 복구 시간을 구한다.
#
# 사용법: load-test/notification/wait-recovery.sh RUN_ID [INTERVAL_SEC] [TIMEOUT_SEC]
set -euo pipefail

RUN_ID="${1:?RUN_ID를 지정해 주세요}"
INTERVAL="${2:-0.5}"
TIMEOUT="${3:-600}"
DIR="$(cd "$(dirname "$0")" && pwd)"
ORDERS="$DIR/../results/notification/orders-${RUN_ID}.csv"
PG_CONTAINER="${PG_CONTAINER:-palette-postgres}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-palette-kafka}"
order_ids="$(tail -n +2 "$ORDERS" | cut -d, -f1 | paste -sd, -)"

now() { python3 -c 'import datetime; print(datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3])'; }
epoch() { python3 -c 'import time; print(time.time())'; }
# notification 그룹의 토픽별 lag 합 (컨슈머가 없으면 그 시점 커밋 기준)
lag() {
  docker exec "$KAFKA_CONTAINER" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --describe --group notification 2>/dev/null \
    | awk '$2 ~ /events$/ && $6 ~ /^[0-9]+$/ {s[$2] += $6} END {for (t in s) printf "%s=%d ", t, s[t]}'
}

query="WITH run_events AS (
  SELECT event_id FROM market.outbox_event WHERE event_type = 'OrderPaidEvent' AND aggregate_id IN (${order_ids})
  UNION ALL
  SELECT e.event_id FROM delivery.outbox_event e JOIN delivery.delivery d ON d.id = e.aggregate_id
   WHERE d.order_id IN (${order_ids}) AND e.event_type IN ('DeliveryShippedEvent', 'DeliveryCompletedEvent'))
SELECT count(*) FROM run_events r WHERE NOT EXISTS (
  SELECT 1 FROM notification.processed_event p WHERE p.event_id = r.event_id AND p.consumer LIKE 'notification-%');"

start=$(epoch)
echo "$(now) 확인 시작 (RUN_ID=${RUN_ID})"
while :; do
  missing=$(docker exec "$PG_CONTAINER" psql -U palette -d palette -tAc "$query")
  echo "$(now) 유실 ${missing}건 lag $(lag)"
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
