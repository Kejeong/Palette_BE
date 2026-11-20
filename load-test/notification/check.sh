#!/usr/bin/env bash
# 알림 장애 측정 확인: 이번 실행 주문 기준으로 주문·배송 상태, 이벤트 발행, 알림 유실·중복을 센다.
# 기대값: 주문 1건당 알림 3건(ORDER_PAID·DELIVERY_STARTED·DELIVERY_COMPLETED)
#
# 사용법: load-test/notification/check.sh RUN_ID
set -euo pipefail

RUN_ID="${1:?RUN_ID를 지정해 주세요}"
DIR="$(cd "$(dirname "$0")" && pwd)"
ORDERS="$DIR/../results/notification/orders-${RUN_ID}.csv"
PG_CONTAINER="${PG_CONTAINER:-palette-postgres}"
order_ids="$(tail -n +2 "$ORDERS" | cut -d, -f1 | paste -sd, -)"

docker exec -i "$PG_CONTAINER" psql -U palette -d palette <<SQL
\\pset footer off
\\echo '== 주문 상태'
SELECT status, count(*) FROM market.market_order WHERE id IN (${order_ids}) GROUP BY 1;
\\echo '== 배송 상태'
SELECT status, count(*) FROM delivery.delivery WHERE order_id IN (${order_ids}) GROUP BY 1;

-- 이번 실행 이벤트: 결제(market outbox, aggregate=주문) + 배송 시작·완료(delivery outbox, aggregate=배송)
CREATE TEMP VIEW run_events AS
  SELECT event_id, event_type, status FROM market.outbox_event
   WHERE event_type = 'OrderPaidEvent' AND aggregate_id IN (${order_ids})
  UNION ALL
  SELECT e.event_id, e.event_type, e.status FROM delivery.outbox_event e
    JOIN delivery.delivery d ON d.id = e.aggregate_id
   WHERE d.order_id IN (${order_ids}) AND e.event_type IN ('DeliveryShippedEvent', 'DeliveryCompletedEvent');

\\echo '== 이벤트 발행 (outbox)'
SELECT event_type, status, count(*) FROM run_events GROUP BY 1, 2 ORDER BY 1;

\\echo '== 알림 (유형별)'
SELECT type, count(*) FROM notification.notification WHERE order_id IN (${order_ids}) GROUP BY 1 ORDER BY 1;

\\echo '== 요약'
SELECT
  (SELECT count(*) FROM run_events) AS events,
  (SELECT count(*) FROM notification.processed_event p JOIN run_events r ON r.event_id = p.event_id
    WHERE p.consumer LIKE 'notification-%') AS processed,
  (SELECT count(*) FROM notification.notification WHERE order_id IN (${order_ids})) AS notifications,
  -- 유실: 이벤트는 발행됐는데 그 이벤트의 알림이 없음
  (SELECT count(*) FROM run_events r WHERE NOT EXISTS (
      SELECT 1 FROM notification.processed_event p WHERE p.event_id = r.event_id AND p.consumer LIKE 'notification-%')) AS missing,
  -- 중복: 같은 주문·유형 알림이 2건 이상인 초과분
  (SELECT coalesce(sum(c - 1), 0) FROM (
      SELECT count(*) c FROM notification.notification WHERE order_id IN (${order_ids})
       GROUP BY member_id, order_id, type HAVING count(*) > 1) d) AS duplicates;
SQL
