"""
충전 PG 장애 측정용 가짜 헥토파이낸셜 서버 (로컬 전용, 표준 라이브러리 + openssl 명령만 사용)

payment-service를 HECTO_API_BASE_URL=http://localhost:9095 와 아래 측정용 키로 띄우면 헥토 대신 이 서버를 부름
  HECTO_MCHT_ID=fakemcht  HECTO_AES_KEY=fake-hecto-aes-key-32-bytes-0000  HECTO_HASH_KEY=fake-hecto-hash-key
측정 순서는 load-test/README.md 참고

실행: python3 -I load-test/topup/fake-pg/fake_hecto.py [PORT]   (기본 9095)

모드 (실행 중에 바꿈, fake_toss.py와 같음)
  normal  : 승인 요청을 바로 승인(0021)
  error   : 승인·조회·취소 모두 HTTP 500 (PG 서버 오류)
  timeout : 승인은 해 두고 DELAY초 동안 응답하지 않음 (응답 유실)
  hang    : timeout에 더해 조회·취소도 DELAY초 동안 응답하지 않음 (PG가 응답 없이 멈춤. 대사 조회도 끊김)

API (실제 헥토와 같은 경로·형식: {"params": {...}, "data": {...}}, 값은 모두 문자열)
  POST /spay/APIService.do   승인. 같은 인증 거래번호를 다시 승인하면 ST50(중복결제)
  POST /spay/APITrdcheck.do  조회. 원거래 주문번호(orgMchtTrdNo)로 찾고, 없으면 ST06(거래 없음)
  POST /spay/APICancel.do    부분 취소

관리용 API
  curl -X POST 'localhost:9095/__mode?value=error'           모드 변경
  curl 'localhost:9095/__stats?prefix=fake-after-error-'     모드·요청 수·승인 수 (거래번호 앞부분으로 거름)
  curl -X POST localhost:9095/__reset                        승인 기록 비우기
"""
import base64
import hashlib
import json
import subprocess
import sys
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 9095
DELAY_SECONDS = 35  # payment-service의 헥토 응답 대기(30초)보다 길게
MODES = ("normal", "error", "timeout", "hang")

# payment-service를 띄울 때 넣는 측정용 키와 같아야 함 (AES 키는 32바이트)
MCHT_ID = "fakemcht"
AES_KEY = b"fake-hecto-aes-key-32-bytes-0000"
HASH_KEY = "fake-hecto-hash-key"

lock = threading.Lock()
state = {"mode": "normal", "approve_requests": 0, "inquiry_requests": 0, "cancel_requests": 0}
approved = {}  # 충전 주문번호(mchtTrdNo) → {"trdNo", "amount", "canceled"}


def aes(data, decrypt=False):
    # AES-256-ECB + PKCS5 패딩 (HectoCrypto와 같음). 표준 라이브러리에 AES가 없어 openssl 명령을 씀
    args = ["openssl", "enc", "-aes-256-ecb", "-K", AES_KEY.hex()] + (["-d"] if decrypt else [])
    return subprocess.run(args, input=data, capture_output=True, check=True).stdout


def encrypt_amount(amount):
    return base64.b64encode(aes(str(amount).encode())).decode()


def decrypt_amount(text):
    return int(aes(base64.b64decode(text), decrypt=True).decode())


def pkt_hash(*values):
    return hashlib.sha256(("".join(values) + HASH_KEY).encode()).hexdigest()


def now_dt_tm():
    now = datetime.now()
    return now.strftime("%Y%m%d"), now.strftime("%H%M%S")


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
        self.send_header("Content-Type", "text/plain; charset=utf-8")  # 실제 헥토도 JSON을 text/plain으로 보냄
        try:
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass  # hang 모드에서 상대가 이미 끊음

    def read_json(self):
        # payment-service(RestClient)는 본문을 chunked로 보낼 수 있음
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            body = b""
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    break
                body += self.rfile.read(size)
                self.rfile.readline()
            return json.loads(body or b"{}")
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"{}")

    def do_POST(self):
        url = urlparse(self.path)
        if url.path in ("/spay/APIService.do", "/spay/APITrdcheck.do", "/spay/APICancel.do"):
            body = self.read_json()
            with lock:
                mode = state["mode"]
            if mode == "error":
                self.count(url.path)
                return self.send_json(500, {"message": "가짜 PG 서버 오류"})
            if url.path == "/spay/APIService.do":
                return self.approve(body["params"], body["data"], mode)
            if mode == "hang":
                time.sleep(DELAY_SECONDS)  # 그동안 payment-service는 응답 대기 시간 초과로 끊음
            if url.path == "/spay/APITrdcheck.do":
                return self.inquiry(body["params"], body["data"])
            return self.cancel(body["params"], body["data"])
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
                for key in ("approve_requests", "inquiry_requests", "cancel_requests"):
                    state[key] = 0
            return self.send_json(200, {"reset": True})
        self.send_json(404, {"message": "없는 경로"})

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/__stats":
            prefix = parse_qs(url.query).get("prefix", [""])[0]
            with lock:
                count = sum(1 for p in approved.values() if p["trdNo"].startswith(prefix))
                body = {**state, "approved": count, "prefix": prefix}
            return self.send_json(200, body)
        self.send_json(404, {"message": "없는 경로"})

    def count(self, path):
        key = {"/spay/APIService.do": "approve_requests", "/spay/APITrdcheck.do": "inquiry_requests"}.get(path, "cancel_requests")
        with lock:
            state[key] += 1

    def result(self, params, out_stat_cd, out_rslt_cd, msg, trd_no="", data=None):
        trd_dt, trd_tm = now_dt_tm()
        return {
            "params": {"mchtId": MCHT_ID, "mchtTrdNo": params.get("mchtTrdNo", ""), "outStatCd": out_stat_cd,
                       "outRsltCd": out_rslt_cd, "outRsltMsg": msg, "trdNo": trd_no, "trdDt": trd_dt, "trdTm": trd_tm},
            "data": data or {},
        }

    def approve(self, params, data, mode):
        self.count("/spay/APIService.do")
        order_id, auth_trd_no = params["mchtTrdNo"], data["authTrdNo"]
        amount = decrypt_amount(data["trdAmt"])

        with lock:
            duplicate = order_id in approved
            if not duplicate:
                approved[order_id] = {"trdNo": auth_trd_no, "amount": amount, "canceled": 0}
        if duplicate:
            return self.send_json(200, self.result(params, "0031", "ST50", "중복결제", auth_trd_no))

        response = self.result(params, "0021", "0000", "정상", auth_trd_no)
        p = response["params"]
        response["data"] = {"trdAmt": encrypt_amount(amount),
                            "pktHash": pkt_hash(p["outStatCd"], p["trdDt"], p["trdTm"], MCHT_ID, order_id, str(amount))}
        if mode in ("timeout", "hang"):
            time.sleep(DELAY_SECONDS)  # 그동안 payment-service는 응답 대기 시간 초과로 끊음
        try:
            self.send_json(200, response)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def inquiry(self, params, data):
        self.count("/spay/APITrdcheck.do")
        with lock:
            payment = approved.get(data.get("orgMchtTrdNo", ""))
        if payment is None:
            return self.send_json(200, self.result(params, "0031", "ST06", "거래 없음"))
        code = "0001" if payment["canceled"] > 0 else "0000"
        return self.send_json(200, self.result(params, "0021", code, "정상", payment["trdNo"],
                                               {"trdAmt": str(payment["amount"]), "cnclAmt": str(payment["canceled"])}))

    def cancel(self, params, data):
        self.count("/spay/APICancel.do")
        amount = decrypt_amount(data["cnclAmt"])
        with lock:
            payment = approved.get(params["mchtTrdNo"])
            if payment is not None and payment["canceled"] + amount <= payment["amount"]:
                payment["canceled"] += amount
                balance = payment["amount"] - payment["canceled"]
            else:
                balance = None
        if balance is None:
            return self.send_json(200, self.result(params, "0031", "ST57", "취소 금액 초과"))
        return self.send_json(200, self.result(params, "0021", "0000", "정상", payment["trdNo"],
                                               {"cnclAmt": encrypt_amount(amount), "blcAmt": encrypt_amount(balance)}))


if __name__ == "__main__":
    print(f"가짜 헥토 서버 시작: http://localhost:{PORT} (모드 normal)", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
