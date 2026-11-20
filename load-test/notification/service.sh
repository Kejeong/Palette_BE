#!/usr/bin/env bash
# 알림 장애 측정용: delivery·notification을 bootRun으로 켜고 끈다. 켠 시각·끈 시각을 찍고 서버 로그는 파일로 남긴다.
# SQL 로그(local 프로필)는 측정에 섞이지 않게 끈다. Delivery Tracker는 가짜 값·웹훅 끔(외부 호출 안 함).
#
# 사용법: load-test/notification/service.sh start|stop|status delivery|notification [LOG_NAME]
#   start 로그: load-test/results/notification/server-{서비스}-{LOG_NAME}.log (기본 LOG_NAME=run)
set -euo pipefail

ACTION="${1:?start|stop|status}"
SERVICE="${2:?delivery|notification}"
LOG_NAME="${3:-run}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
case "$SERVICE" in
  delivery) PORT=8085 ;;
  notification) PORT=8086 ;;
  *) echo "delivery 또는 notification"; exit 1 ;;
esac

now() { python3 -c 'import datetime; print(datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3])'; }
pid_on_port() { lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1; }

case "$ACTION" in
  start)
    log="$ROOT/load-test/results/notification/server-${SERVICE}-${LOG_NAME}.log"
    echo "$(now) ${SERVICE} 시작 (로그 ${log})"
    (cd "$ROOT" && \
      SPRING_PROFILES_ACTIVE=local \
      SPRING_JPA_SHOW_SQL=false \
      SPRING_JPA_PROPERTIES_HIBERNATE_FORMAT_SQL=false \
      DELIVERY_TRACKER_API_URL=http://localhost:9/graphql \
      DELIVERY_TRACKER_CLIENT_ID=load-test \
      DELIVERY_TRACKER_CLIENT_SECRET=load-test \
      DELIVERY_TRACKER_CALLBACK_BASE_URL=http://localhost:8085/webhooks/delivery-tracker \
      DELIVERY_TRACKER_CALLBACK_TOKEN=load-test-callback-token-0000000000000000 \
      DELIVERY_TRACKER_WEBHOOK_ENABLED=false \
      nohup ./gradlew ":${SERVICE}-service:bootRun" < /dev/null > "$log" 2>&1 &)
    for _ in $(seq 1 180); do
      if [ -n "$(pid_on_port)" ]; then echo "$(now) ${SERVICE} 포트 ${PORT} 열림"; exit 0; fi
      sleep 1
    done
    echo "$(now) 180초 안에 뜨지 않음. 로그 확인: ${log}"; exit 1
    ;;
  stop)
    pid="$(pid_on_port)"
    if [ -z "$pid" ]; then echo "$(now) ${SERVICE} 이미 꺼져 있음"; exit 0; fi
    echo "$(now) ${SERVICE} 종료 (pid ${pid})"
    kill "$pid"
    while [ -n "$(pid_on_port)" ]; do sleep 0.2; done
    echo "$(now) ${SERVICE} 포트 ${PORT} 닫힘"
    ;;
  status)
    pid="$(pid_on_port)"; [ -n "$pid" ] && echo "${SERVICE} 실행 중 (pid ${pid})" || echo "${SERVICE} 꺼짐"
    ;;
esac
