#!/usr/bin/env bash
# 개발 서버(EC2)에서 바뀐 앱만 다시 빌드·실행하고 앱이 제대로 실행됐는지 확인한다.
#
# 자동 배포: CI가 통과하면 GitHub Actions가 배포 전용 키로 SSH 접속한다. 서버의 authorized_keys에 고정한
#   infra/scripts/deploy-remote.sh가 그 커밋 SHA를 검증·체크아웃한 뒤 이 스크립트를 실행한다.
# 수동 배포: cd ~/palette && git pull && bash infra/scripts/deploy.sh
#   앱을 전부 다시 빌드하려면: DEPLOY_ALL=1 bash infra/scripts/deploy.sh
#
# 서버의 .env(infra/.env)는 Git이 관리하지 않으므로 배포해도 그대로 남는다.

set -euo pipefail

# 앱 서비스 목록. compose.prod.yaml의 서비스 이름이고, 소스 폴더는 module_dir(gateway만 gateway, 나머지는 <이름>-service), 컨테이너는 palette-<이름>이다.
# 서비스가 분리되면 여기에 추가한다.
APP_SERVICES=(gateway member market payment settlement delivery notification ai)

# 서비스 이름 → 소스 폴더(Gradle 모듈) 이름
module_dir() {
    if [ "$1" = gateway ]; then echo gateway; else echo "$1-service"; fi
}

# 모니터링 서비스 목록. infra/monitoring/<이름>/ 설정 파일을 bind mount하는 compose 서비스다.
# 첫 배포·DEPLOY_ALL=1이면 전부 재시작해 설정을 다시 읽게 한다.
MONITORING_SERVICES=(alloy grafana loki prometheus)

# 모든 앱 이미지에 들어가는 파일. 하나라도 바뀌면 앱을 전부 다시 빌드한다.
#   common/(공통 코드), Gradle 설정, Dockerfile, infra/compose.prod.yaml(앱 build.args 등이 여기 있어 바뀌면 이미지를 다시 만든다)
SHARED_PATHS='^(common/|gradle/|gradlew$|build\.gradle\.kts$|settings\.gradle\.kts$|lombok\.config$|Dockerfile$|infra/compose\.prod\.yaml$)'

# 마지막으로 배포에 성공한 커밋. 이번 커밋과 비교해 바뀐 앱을 고른다. 저장소 밖(서버 홈)에 둬서 git이 건드리지 않는다
STATE_FILE="${DEPLOY_STATE_FILE:-$HOME/.palette-last-deployed}"

# 실패하면 마지막에 "어느 단계에서, 왜"를 두 줄로 남긴다. deploy.yml이 이 두 줄을 Slack 실패 알림에 붙인다.
#   ❌ 단계: 앱 실행 확인
#   ❌ 원인: palette-market 상태가 unhealthy입니다
# 원인을 따로 정하지 못한 실패(빌드 오류 등)는 단계만 알리고 자세한 내용은 Actions 로그에서 본다.
DEPLOY_STAGE="배포 준비"
DEPLOY_REASON=""
report_failure() {
    local rc=$?
    end_gateway_alert_silence   # 성공·실패 모두 배포 중 무음을 푼다(❌ 두 줄보다 먼저 출력되게 여기서 부른다)
    [ "$rc" -eq 0 ] && return
    echo "❌ 단계: ${DEPLOY_STAGE}"
    echo "❌ 원인: ${DEPLOY_REASON:-${DEPLOY_STAGE} 중 명령이 실패했습니다 (위 로그 참고)}"
}
trap report_failure EXIT

# 배포하는 동안 "gateway 서비스 연결 실패" 알림(infra/monitoring/grafana/provisioning/alerting/rules.yaml)만 Grafana 무음(silence)으로 꺼 둔다.
# 서비스가 재시작되는 동안 gateway가 연결에 실패하는 건 예상된 일이라서다. 그 밖의 알림(앱 ERROR 로그)은 끄지 않는다.
#   - 스크립트가 중간에 죽어도 무음이 계속 남지 않게, 걸 때 끝나는 시각을 정해 둔다(배포 대기 상한 + 5분)
#   - 배포가 끝나면(성공·실패 모두, 위 trap) 끝나는 시각을 2분 뒤로 당긴다. 규칙이 최근 1분만 세므로 배포 중 실패가 빠진 뒤에 풀린다
#   - Grafana가 꺼져 있거나 응답이 없으면 경고만 남기고 배포는 계속한다
# Grafana는 서버 안에서만 열린 127.0.0.1:3000에 /grafana 아래로 뜬다(compose.prod.yaml). 관리자 비밀번호는 infra/.env에 있다
GRAFANA_SILENCE_API="http://127.0.0.1:3000/grafana/api/alertmanager/grafana/api/v2/silences"
GATEWAY_ALERT_RULE_UID="palette-gateway-connection-failure"
GATEWAY_SILENCE_ID=""

# 무음을 새로 걸거나(첫 인자 비움) 이미 건 무음의 끝 시각을 바꾼다(첫 인자 = 무음 id). 성공하면 무음 id를 출력한다
put_gateway_alert_silence() {
    local id="$1" ends_in_seconds="$2" password body
    password="$(grep -m1 '^GRAFANA_ADMIN_PASSWORD=' infra/.env | cut -d= -f2- | tr -d '\r"')"
    body="$(printf '{%s"matchers":[{"name":"__alert_rule_uid__","value":"%s","isRegex":false,"isEqual":true}],"startsAt":"%s","endsAt":"%s","createdBy":"deploy.sh","comment":"배포 중 서비스 재시작으로 gateway 연결 실패가 예상됨"}' \
        "${id:+\"id\":\"$id\",}" "$GATEWAY_ALERT_RULE_UID" \
        "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$(date -u -d "+${ends_in_seconds} seconds" +%Y-%m-%dT%H:%M:%SZ)")"
    curl -fsS -m 5 -u "admin:${password}" -H 'Content-Type: application/json' -d "$body" "$GRAFANA_SILENCE_API" \
        | sed -n 's/.*"silenceID":"\([^"]*\)".*/\1/p'
}

end_gateway_alert_silence() {
    [ -z "$GATEWAY_SILENCE_ID" ] && return 0
    put_gateway_alert_silence "$GATEWAY_SILENCE_ID" 120 > /dev/null \
        || echo "⚠ gateway 연결 실패 알림 무음의 끝 시각을 바꾸지 못했습니다. 처음 정한 시각에 풀립니다"
}

# 스크립트 전체를 함수로 읽은 뒤 실행한다. 실행 중 git이 이 파일을 바꿔도 영향받지 않게 하기 위함이다.
main() {
    local app_dir="${APP_DIR:-$HOME/palette}"
    # 앱과 별개로, 재시작한 nginx(80)가 요청을 앱으로 넘기는지 본다. 로그인 없이 열리는 경로를 쓴다
    local nginx_url="http://localhost/v3/api-docs"
    # 앱 실행 확인 상한(초). 앱이 뜨는 중(starting)이면 이 시간까지 기다린다. 정상이면 닿지 않는 안전장치다
    local wait_limit="${DEPLOY_WAIT_LIMIT:-600}"
    local compose=(docker compose -f infra/compose.prod.yaml)

    cd "$app_dir"

    # 배포가 겹치면 뒤에 온 배포는 앞 배포가 끝날 때까지 기다린다.
    exec 9>/tmp/palette-deploy.lock
    flock 9

    echo "▶ 배포 대상: $(git log -1 --format='%h %s')"
    local current
    current="$(git rev-parse HEAD)"

    # 다시 빌드한 앱 이미지에 배포 커밋을 넣는다(Dockerfile의 APP_VERSION). 로그 service.version → Slack 에러 알림의 커밋 링크
    export APP_VERSION="${current:0:7}"

    DEPLOY_STAGE="바뀐 앱 고르기"
    echo "▶ 바뀐 앱 고르기"
    pick_changed_apps "$current"

    DEPLOY_STAGE="빌드·재시작"
    if [ "${#BUILD_SERVICES[@]}" -gt 0 ]; then
        echo "▶ 빌드: ${BUILD_SERVICES[*]}"
        # Dockerfile의 공통 빌드 단계가 바뀐 앱 jar만 만들게 한다(기본은 jar 전부). 예: "gateway market-service"
        local modules="" s
        for s in "${BUILD_SERVICES[@]}"; do modules+="$(module_dir "$s") "; done
        "${compose[@]}" build --build-arg SERVICES="$modules" "${BUILD_SERVICES[@]}"
    else
        echo "▶ 빌드: 바뀐 앱이 없어 건너뜁니다"
    fi
    # 재시작하는 동안 gateway 연결 실패 알림을 끈다(위 put_gateway_alert_silence 설명 참고)
    GATEWAY_SILENCE_ID="$(put_gateway_alert_silence "" $(( wait_limit + 300 )) || true)"
    if [ -n "$GATEWAY_SILENCE_ID" ]; then
        echo "▶ gateway 연결 실패 알림 무음 (배포가 끝나고 2분 뒤 풀림)"
    else
        echo "⚠ gateway 연결 실패 알림을 무음으로 하지 못했습니다(Grafana 확인). 배포는 계속합니다"
    fi

    # 이미지나 설정(compose.prod.yaml)이 바뀐 컨테이너만 다시 만든다. 그대로인 앱은 재시작되지 않는다
    echo "▶ 재시작 (이미지·설정이 바뀐 컨테이너만)"
    "${compose[@]}" up -d --remove-orphans

    # infra/nginx/default.conf는 파일 하나를 bind mount해서, git pull로 파일이 바뀌어도 실행 중인 nginx는 예전 파일을 본다.
    # 재시작해야 새 설정을 읽는다(재시작하는 1~2초 동안 요청이 끊기므로 설정이 바뀐 배포에서만 한다).
    DEPLOY_STAGE="nginx 재시작"
    if $RESTART_NGINX; then
        echo "▶ nginx 설정 다시 읽기"
        "${compose[@]}" restart nginx
    else
        # 다시 만든 앱 컨테이너는 IP가 바뀔 수 있지만, nginx는 앱 주소를 요청할 때 찾으므로(nginx/default.conf의 resolver) 그대로 둔다
        echo "▶ nginx: 설정이 그대로라 재시작하지 않습니다"
    fi

    # 모니터링 설정 파일도 bind mount라 git pull만으로 실행 중인 컨테이너에 반영되지 않는다(Grafana provisioning은 뜰 때만 읽음).
    # 모니터링 컨테이너는 앱 실행 확인 대상이 아니라 배포 성공 판단에는 넣지 않는다
    DEPLOY_STAGE="모니터링 재시작"
    if [ "${#RESTART_MONITORING[@]}" -gt 0 ]; then
        echo "▶ 모니터링 설정 다시 읽기: ${RESTART_MONITORING[*]}"
        "${compose[@]}" restart "${RESTART_MONITORING[@]}"
    fi

    # 시간이 아니라 상태로 판단한다(compose.prod.yaml의 x-app-healthcheck).
    #   starting  : 뜨는 중 → 계속 기다린다
    #   healthy   : 다 떴다
    #   unhealthy : 시작 시간(start_period)이 지났는데도 응답이 없다 → 바로 실패
    DEPLOY_STAGE="앱 실행 확인"
    echo "▶ 앱 실행 확인 (unhealthy면 바로 실패, 최대 ${wait_limit}초)"
    local started=$SECONDS last_report=$SECONDS service status waiting
    while true; do
        waiting=()
        for service in "${APP_SERVICES[@]}"; do
            status="$(docker inspect -f '{{.State.Health.Status}}' "palette-${service}" 2>/dev/null || echo "없음")"
            case "$status" in
                healthy) ;;
                starting) waiting+=("$service") ;;
                없음)
                    fail_with_logs "palette-${service} 컨테이너가 없습니다 (떴다가 종료됐을 수 있음)" "$service"
                    return 1
                    ;;
                *)
                    fail_with_logs "palette-${service} 상태가 ${status}입니다" "$service"
                    return 1
                    ;;
            esac
        done

        # 앱이 다 떴으면 nginx를 거쳐서도 응답하는지 본다(nginx가 막 재시작했으면 잠깐 실패할 수 있어 다시 본다)
        if [ "${#waiting[@]}" -eq 0 ] && curl -fsS -o /dev/null "$nginx_url" 2>/dev/null; then
            echo "✅ 배포 완료 ($(( SECONDS - started ))초)"
            # 다음 배포는 이 커밋과 비교한다. 실패한 배포는 기록하지 않아 다음 배포에서 다시 빌드된다
            echo "$current" > "$STATE_FILE"
            docker image prune -f > /dev/null   # 이전 빌드에서 남은 이미지 정리 (디스크 50GB 제한)
            return 0
        fi

        if (( SECONDS - started >= wait_limit )); then
            fail_with_logs "${wait_limit}초 안에 끝나지 않았습니다. 아직 뜨는 중: ${waiting[*]:-nginx}" "${waiting[@]}"
            return 1
        fi

        # 30초마다 아직 뜨는 중인 앱을 알려 준다(Actions 로그에서 어디서 오래 걸리는지 보이게)
        if (( SECONDS - last_report >= 30 )); then
            echo "  … $(( SECONDS - started ))초, 뜨는 중: ${waiting[*]:-없음 (nginx 확인 중)}"
            last_report=$SECONDS
        fi
        sleep 5
    done
}

# 마지막 배포 커밋과 이번 커밋을 비교해 다시 빌드할 앱(BUILD_SERVICES), nginx 재시작 여부(RESTART_NGINX),
# 재시작할 모니터링 컨테이너(RESTART_MONITORING)를 정한다.
#   - 기록이 없거나(첫 배포) 비교할 수 없으면, 또는 DEPLOY_ALL=1이면 → 앱 전부, nginx·모니터링 재시작
#   - 공통 파일(SHARED_PATHS)이 바뀌면 → 앱 전부
#   - 그 앱 소스 폴더(module_dir, 예: gateway/, market-service/) 아래가 바뀌면 → 그 앱만
#   - infra/nginx/ 아래가 바뀌면 → nginx 재시작
#   - infra/monitoring/<이름>/ 아래가 바뀌면 → 같은 이름의 compose 서비스 재시작(alloy·grafana·loki·prometheus)
pick_changed_apps() {
    local current="$1" last="" changed shared service monitoring
    BUILD_SERVICES=()
    RESTART_NGINX=false
    RESTART_MONITORING=()
    [ -f "$STATE_FILE" ] && last="$(cat "$STATE_FILE")"

    if [ "${DEPLOY_ALL:-0}" = 1 ]; then
        echo "  DEPLOY_ALL=1 → 앱 전부"
        BUILD_SERVICES=("${APP_SERVICES[@]}")
        RESTART_NGINX=true
        RESTART_MONITORING=("${MONITORING_SERVICES[@]}")
        return
    fi
    # 기록한 커밋이 서버 git에 없거나, 이번 커밋의 조상이 아니면(되돌린 배포 등) 비교하지 않고 전부 빌드한다
    if [ -z "$last" ] || ! git merge-base --is-ancestor "$last" "$current" 2>/dev/null; then
        echo "  마지막 배포 기록이 없거나 비교할 수 없음 → 앱 전부"
        BUILD_SERVICES=("${APP_SERVICES[@]}")
        RESTART_NGINX=true
        RESTART_MONITORING=("${MONITORING_SERVICES[@]}")
        return
    fi

    changed="$(git diff --name-only "$last" "$current")"
    echo "  ${last:0:7} → ${current:0:7}, 바뀐 파일 $(grep -c . <<< "$changed" || true)개"

    shared="$(grep -E "$SHARED_PATHS" <<< "$changed" || true)"
    if [ -n "$shared" ]; then
        echo "  공통 파일이 바뀜 → 앱 전부 ($(head -3 <<< "$shared" | tr '\n' ' '))"
        BUILD_SERVICES=("${APP_SERVICES[@]}")
    else
        for service in "${APP_SERVICES[@]}"; do
            grep -q "^$(module_dir "$service")/" <<< "$changed" && BUILD_SERVICES+=("$service")
        done
    fi
    grep -q '^infra/nginx/' <<< "$changed" && RESTART_NGINX=true
    # 바뀐 파일이 없으면 grep이 실패하므로 || true (set -e)
    monitoring="$(grep -oE '^infra/monitoring/[a-z]+/' <<< "$changed" || true)"
    if [ -n "$monitoring" ]; then
        RESTART_MONITORING=($(cut -d/ -f3 <<< "$monitoring" | sort -u))
    fi
    return 0
}

# 실패 이유와 함께 컨테이너 상태, 문제 서비스의 최근 로그를 보여 준다(서비스를 못 집으면 앱 전체)
fail_with_logs() {
    DEPLOY_REASON="$1"
    shift
    local services=("$@")
    [ "${#services[@]}" -eq 0 ] && services=("${APP_SERVICES[@]}")

    echo "✖ ${DEPLOY_REASON}"
    docker compose -f infra/compose.prod.yaml ps
    echo "▶ 최근 로그: ${services[*]}"
    docker compose -f infra/compose.prod.yaml logs --tail 80 "${services[@]}"
}

main "$@"
exit $?
