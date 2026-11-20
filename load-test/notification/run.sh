#!/usr/bin/env bash
# 알림 장애 측정 한 번: 결제 → 배송 생성 대기 → 배송 시작 → 배송 완료를 k6로 차례로 실행한다.
# 주문은 먼저 prepare-orders.py RUN_ID로 준비한다. 단계마다 요약을 results/notification/{RUN_ID}-{단계}.json에 남긴다.
# 배송 id는 주문 id로 찾는 API가 없어 DB에서 뽑는다 (측정 시간에는 들어가지 않음)
#
# 사용법: load-test/notification/run.sh RUN_ID [VUS]   (기본 10)
set -euo pipefail

RUN_ID="${1:?RUN_ID를 지정해 주세요}"
VUS="${2:-10}"
DIR="$(cd "$(dirname "$0")" && pwd)"
RESULTS="$DIR/../results/notification"
PG_CONTAINER="${PG_CONTAINER:-palette-postgres}"
ORDERS="$RESULTS/orders-${RUN_ID}.csv"
[ -f "$ORDERS" ] || { echo "$ORDERS 없음. prepare-orders.py ${RUN_ID} 먼저 실행"; exit 1; }

now() { python3 -c 'import datetime; print(datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3])'; }
psql_q() { docker exec "$PG_CONTAINER" psql -U palette -d palette -tAc "$1"; }
order_ids="$(tail -n +2 "$ORDERS" | cut -d, -f1 | paste -sd, -)"
count="$(tail -n +2 "$ORDERS" | wc -l | tr -d ' ')"

phase() {
  echo "$(now) ${1} 시작"
  k6 run -q -e PHASE="$1" -e RUN_ID="$RUN_ID" -e VUS="$VUS" \
    --summary-export="$RESULTS/${RUN_ID}-${1}.json" "$DIR/notification-failure.js" 2>&1 | grep -E "${1}_success|http_req_failed|checks" || true
  echo "$(now) ${1} 끝"
}

phase pay

# delivery가 order.events를 받아 배송(READY)을 만들 때까지 기다림 (알림과는 별개 컨슈머)
echo "$(now) 배송 생성 대기 (${count}건)"
for _ in $(seq 1 120); do
  ready="$(psql_q "SELECT count(*) FROM delivery.delivery WHERE order_id IN (${order_ids}) AND status = 'READY'")"
  [ "$ready" -ge "$count" ] && break
  sleep 0.5
done
echo "$(now) 배송 READY ${ready}/${count}건"
{ echo 'deliveryId,orderId'; psql_q "SELECT id || ',' || order_id FROM delivery.delivery WHERE order_id IN (${order_ids}) AND status = 'READY' ORDER BY id"; } \
  > "$RESULTS/deliveries-${RUN_ID}.csv"

phase ship
sleep 1.1   # 배송 완료 시각(초 단위)이 송장 등록 시각보다 이르지 않게
phase complete
