#!/usr/bin/env python3
# 알림 장애 측정용: 결제 전 주문 COUNT건을 만든다. 주문은 15분 뒤 만료되므로 k6 실행 직전에 돌린다.
# 구매자는 충전 측정 때 만든 회원 load-topup-user-{0..BUYERS-1}@test.com (비밀번호 Load1234!)을 쓴다.
#
# - 3만 원 이상(무료 배송)이 되게 수량을 정한다. 배송비가 있으면 결제 때 플랫폼(관리자) 지갑이 필요한데 로컬에는 없음
# - 같은 옵션을 다시 담으면 결제 전 주문의 장바구니 항목에 합쳐져 주문할 수 없으므로 구매자마다 다른 옵션을 쓴다
# - 구매자 잔액·옵션 재고가 모자라지 않게 SQL로 올려 둔다 (로컬 전용)
#
# 사용법: load-test/notification/prepare-orders.py RUN_ID [COUNT] [BUYERS]   (기본 200, 10)
# 결과: load-test/results/notification/orders-{RUN_ID}.csv (orderId,buyerIndex,memberId)
import json
import math
import os
import subprocess
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

RUN_ID = sys.argv[1] if len(sys.argv) > 1 else sys.exit('RUN_ID를 지정해 주세요')
COUNT = int(sys.argv[2]) if len(sys.argv) > 2 else 200
BUYERS = int(sys.argv[3]) if len(sys.argv) > 3 else 10
MEMBER_URL = os.environ.get('MEMBER_URL', 'http://localhost:8081')
MARKET_URL = os.environ.get('MARKET_URL', 'http://localhost:8082')
PG_CONTAINER = os.environ.get('PG_CONTAINER', 'palette-postgres')
FREE_SHIPPING = 30000
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
OUT = os.path.join(ROOT, 'load-test', 'results', 'notification', f'orders-{RUN_ID}.csv')


def sql(query):
    out = subprocess.run(['docker', 'exec', PG_CONTAINER, 'psql', '-U', 'palette', '-d', 'palette', '-tAc', query],
                         check=True, capture_output=True, text=True).stdout
    return [line.split('|') for line in out.strip().splitlines() if line]


def call(method, url, token=None, body=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = f'Bearer {token}'
    req = urllib.request.Request(url, method=method, headers=headers,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return json.load(res)
    except urllib.error.HTTPError as e:
        return json.load(e)


def email(i):
    return f'load-topup-user-{i}@test.com'


def prepare_buyer(i, per_buyer, options):
    token = call('POST', f'{MEMBER_URL}/api/v1/auth/login', body={'email': email(i), 'password': 'Load1234!'})['data']['accessToken']
    # 이미 장바구니에 있는 옵션(이전 실행의 결제 전 주문 등)은 피함
    in_cart = {item['optionId'] for item in call('GET', f'{MARKET_URL}/api/v1/cart', token)['data']['items']}
    usable = [o for o in options if o[0] not in in_cart]
    if len(usable) < per_buyer:
        raise SystemExit(f'구매자 {i}: 쓸 수 있는 옵션 {len(usable)}개 < 주문 {per_buyer}건')

    orders = []
    for option_id, price in usable[:per_buyer]:
        quantity = math.ceil(FREE_SHIPPING / price)
        added = call('POST', f'{MARKET_URL}/api/v1/cart/items', token, {'optionId': option_id, 'quantity': quantity})
        if added['resultCode'] != '200-1':
            raise SystemExit(f'구매자 {i} 장바구니 담기 실패: {added}')
        cart = call('GET', f'{MARKET_URL}/api/v1/cart', token)['data']
        item = next(it for it in cart['items'] if it['optionId'] == option_id)
        created = call('POST', f'{MARKET_URL}/api/v1/orders', token, {
            'cartItemIds': [item['itemId']],
            'expectedTotalPrice': item['lineAmount'],
            'shippingAddress': {'receiverName': 'k6', 'receiverPhone': '01012345678', 'zipCode': '12345', 'address': 'Seoul'},
        })
        if created['resultCode'] != '201-1':
            raise SystemExit(f'구매자 {i} 주문 생성 실패: {created}')
        orders.append(created['data']['orderId'])
    return i, orders


def main():
    emails = ','.join(f"'{email(i)}'" for i in range(BUYERS))
    members = {row[1]: int(row[0]) for row in sql(f'SELECT id, email FROM member.member WHERE email IN ({emails})')}
    if len(members) < BUYERS:
        sys.exit(f'구매자 {BUYERS}명 중 {len(members)}명만 있음. load-test/topup/prepare-topup-users.sh {BUYERS} 먼저 실행')
    ids = ','.join(str(v) for v in members.values())

    # 주문 1건 최대 약 6만 원 → 구매자당 넉넉히 1천만 원 이상 유지
    sql(f'UPDATE payment.wallet SET balance = balance + 10000000 WHERE member_id IN ({ids}) AND balance < 10000000')
    sql("UPDATE market.option_stock s SET stock = 10000 FROM market.product_option o JOIN market.product p ON p.id = o.product_id "
        "WHERE s.option_id = o.id AND p.status = 'ON_SALE' AND s.stock < 10000")
    options = [(int(r[0]), int(r[1])) for r in sql(
        "SELECT o.id, p.price FROM market.product_option o JOIN market.product p ON p.id = o.product_id "
        "WHERE p.status = 'ON_SALE' ORDER BY o.id")]

    per_buyer = math.ceil(COUNT / BUYERS)
    counts = [min(per_buyer, COUNT - i * per_buyer) for i in range(BUYERS)]
    with ThreadPoolExecutor(BUYERS) as pool:
        results = list(pool.map(lambda i: prepare_buyer(i, counts[i], options), [i for i in range(BUYERS) if counts[i] > 0]))

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, 'w') as f:
        f.write('orderId,buyerIndex,memberId\n')
        for i, orders in results:
            for order_id in orders:
                f.write(f'{order_id},{i},{members[email(i)]}\n')
    print(f'주문 {sum(len(o) for _, o in results)}건 준비 완료 (RUN_ID={RUN_ID}) → {OUT}')


if __name__ == '__main__':
    main()
