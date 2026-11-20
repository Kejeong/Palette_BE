"""
충전 PG 장애 측정용 가짜 토스 서버 (로컬 전용, 표준 라이브러리만 사용)

payment-service를 CUSTOM_TOSS_BASEURL=http://localhost:9090 으로 띄우면 토스 대신 이 서버를 부름
측정 순서와 장애 주입 방법은 load-test/README.md 참고

실행: python3 -I load-test/topup/fake-pg/fake_toss.py [PORT]   (기본 9090)

모드 (실행 중에 바꿈)
  normal  : 승인 요청을 바로 승인(DONE)
  error   : 승인 요청에 500으로 응답, 승인하지 않음 (PG 서버 오류)
  timeout : 승인은 해 두고 DELAY초 동안 응답하지 않음 (응답 유실. 돈은 빠졌는데 우리는 결과를 모름)
  hang    : timeout에 더해 결제 조회도 DELAY초 동안 응답하지 않음 (PG가 응답 없이 멈춤. 대사 조회도 끊김)

결제 조회 (둘 다 지원)
  GET /v1/payments/{paymentKey}       : 지금 payment-service 대사가 쓰는 경로
  GET /v1/payments/orders/{orderId}   : 이전 커밋(비포 측정 기준)의 대사가 쓰던 경로
  실제 토스는 결제위젯 시크릿 키(test_gsk)로 주문번호 조회를 막지만(404 NOT_FOUND_MERCHANT), 비포 측정을 다시 돌릴 수 있게 남겨 둠

관리용 API
  curl -X POST 'localhost:9090/__mode?value=error'           모드 변경
  curl 'localhost:9090/__stats?prefix=fake-before-normal-'   모드·요청 수·승인 수 (paymentKey 앞부분으로 거름)
  curl -X POST localhost:9090/__reset                        승인 기록 비우기
"""
import json
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9090
DELAY_SECONDS = 35  # payment-service의 토스 응답 대기(30초)보다 길게
KST = timezone(timedelta(hours=9))
MODES = ("normal", "error", "timeout", "hang")

lock = threading.Lock()
state = {"mode": "normal", "confirm_requests": 0, "lookup_requests": 0}
approved = {}  # orderId → 승인된 결제 (조회 API가 돌려줌)
approved_by_key = {}  # paymentKey → 같은 결제 (결제키 조회용)


def payment_json(payment_key, order_id, amount, approved_at):
    return {
        "paymentKey": payment_key,
        "orderId": order_id,
        "status": "DONE",
        "totalAmount": amount,
        "method": "카드",
        "approvedAt": approved_at,
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass  # 요청마다 로그를 찍으면 측정 중 터미널이 넘침

    def handle(self):
        # 응답 대기 시간을 넘겨 payment-service가 연결을 끊으면 다음 요청을 읽다가 끊김 오류가 남. 측정에는 영향 없어 무시함
        try:
            super().handle()
        except (ConnectionResetError, BrokenPipeError):
            pass

    def send_json(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def read_json(self):
        # payment-service(RestClient)는 본문을 Content-Length 없이 chunked로 보냄
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            body = b""
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()  # 마지막 빈 줄
                    break
                body += self.rfile.read(size)
                self.rfile.readline()  # 조각 끝의 줄바꿈
            return json.loads(body or b"{}")
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"{}")

    def do_POST(self):
        url = urlparse(self.path)
        if url.path == "/v1/payments/confirm":
            return self.confirm()
        if url.path == "/__mode":
            value = parse_qs(url.query).get("value", [""])[0]
            if value not in MODES:
                return self.send_json(400, {"message": f"value는 {MODES} 중 하나"})
            with lock:
                state["mode"] = value
            print(f"{datetime.now():%H:%M:%S} 모드 → {value}", flush=True)
            return self.send_json(200, {"mode": value})
        if url.path == "/__reset":
            with lock:
                approved.clear()
                approved_by_key.clear()
                state["confirm_requests"] = 0
                state["lookup_requests"] = 0
            return self.send_json(200, {"reset": True})
        self.send_json(404, {"code": "NOT_FOUND", "message": "없는 경로"})

    def do_GET(self):
        url = urlparse(self.path)
        if url.path.startswith("/v1/payments/orders/"):
            return self.lookup(approved, url.path.rsplit("/", 1)[-1])
        if url.path.startswith("/v1/payments/"):
            return self.lookup(approved_by_key, url.path.rsplit("/", 1)[-1])
        if url.path == "/__stats":
            prefix = parse_qs(url.query).get("prefix", [""])[0]
            with lock:
                count = sum(1 for p in approved.values() if p["paymentKey"].startswith(prefix))
                body = {**state, "approved": count, "prefix": prefix}
            return self.send_json(200, body)
        self.send_json(404, {"code": "NOT_FOUND", "message": "없는 경로"})

    def confirm(self):
        body = self.read_json()
        with lock:
            state["confirm_requests"] += 1
            mode = state["mode"]

        if mode == "error":
            return self.send_json(500, {"code": "FAILED_INTERNAL_SYSTEM_PROCESSING", "message": "가짜 PG 서버 오류"})

        payment = payment_json(body["paymentKey"], body["orderId"], body["amount"],
                               datetime.now(KST).isoformat(timespec="seconds"))
        with lock:
            approved[body["orderId"]] = payment
            approved_by_key[body["paymentKey"]] = payment

        if mode in ("timeout", "hang"):
            time.sleep(DELAY_SECONDS)  # 그동안 payment-service는 응답 대기 시간 초과로 끊음
        try:
            self.send_json(200, payment)
        except (BrokenPipeError, ConnectionResetError):
            pass  # 상대가 이미 끊음

    def lookup(self, store, key):
        with lock:
            state["lookup_requests"] += 1
            mode = state["mode"]
            payment = store.get(key)

        if mode == "error":
            return self.send_json(500, {"code": "FAILED_INTERNAL_SYSTEM_PROCESSING", "message": "가짜 PG 서버 오류"})
        if mode == "hang":
            time.sleep(DELAY_SECONDS)  # 그동안 payment-service는 조회 응답 대기 시간 초과로 끊음
        try:
            if payment is None:
                return self.send_json(404, {"code": "NOT_FOUND_PAYMENT", "message": "존재하지 않는 결제 정보 입니다."})
            self.send_json(200, payment)
        except (BrokenPipeError, ConnectionResetError):
            pass  # 상대가 이미 끊음


if __name__ == "__main__":
    print(f"가짜 토스 서버 시작: http://localhost:{PORT} (모드 normal)", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
