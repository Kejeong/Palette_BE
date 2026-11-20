// 알림 장애 측정: 주문 결제·배송 시작·배송 완료 API 부하 (알림 서비스가 꺼져 있어도 같은지 확인)
// 단계마다 따로 실행한다. 보통은 run.sh가 순서대로 부른다
//
// 실행 예:
//   k6 run -e PHASE=pay -e RUN_ID=baseline -e VUS=10 \
//     --summary-export=load-test/results/notification/baseline-pay.json load-test/notification/notification-failure.js
//
// 환경 변수
//   PHASE      pay(구매자 결제) | ship(물류 송장 등록) | complete(관리자 배송 완료)
//   RUN_ID     prepare-orders.py와 같은 값. pay는 orders-{RUN_ID}.csv, ship·complete는 deliveries-{RUN_ID}.csv를 읽는다
//   VUS        동시 사용자 수 (기본 10)
//   MEMBER_URL·MARKET_URL·DELIVERY_URL  서비스 주소 (기본 8081·8082·8085)
//   LOGISTICS_ID·ADMIN_ID  물류·관리자 회원 id (기본 294, 1). delivery는 X-Member-Id·X-Member-Role 헤더로 인증
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const PHASE = __ENV.PHASE;
const RUN_ID = __ENV.RUN_ID;
const VUS = Number(__ENV.VUS || 10);
const MEMBER_URL = __ENV.MEMBER_URL || 'http://localhost:8081';
const MARKET_URL = __ENV.MARKET_URL || 'http://localhost:8082';
const DELIVERY_URL = __ENV.DELIVERY_URL || 'http://localhost:8085';
const LOGISTICS_ID = __ENV.LOGISTICS_ID || '294';
const ADMIN_ID = __ENV.ADMIN_ID || '1';

if (!['pay', 'ship', 'complete'].includes(PHASE) || !RUN_ID) {
  throw new Error('PHASE(pay|ship|complete)와 RUN_ID를 지정해 주세요');
}

// csv 첫 줄은 헤더. pay: orderId,buyerIndex,memberId / ship·complete: deliveryId,orderId
const file = PHASE === 'pay' ? `orders-${RUN_ID}.csv` : `deliveries-${RUN_ID}.csv`;
const rows = open(`../results/notification/${file}`).trim().split('\n').slice(1).map((line) => line.split(','));

// 성공률: HTTP 200이고 resultCode가 200-1인 비율
const success = new Rate(`${PHASE}_success`);
// 성공한 요청만의 응답 시간
const successDuration = new Trend(`${PHASE}_success_duration`, true);

export const options = {
  scenarios: {
    [PHASE]: { executor: 'shared-iterations', vus: VUS, iterations: rows.length, maxDuration: '10m' },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

// 결제는 구매자 토큰이 필요해 구매자마다 한 번만 로그인한다 (충전 측정 회원, 비밀번호 Load1234!)
export function setup() {
  if (PHASE !== 'pay') return {};
  const tokens = {};
  for (const index of new Set(rows.map((r) => r[1]))) {
    const res = http.post(`${MEMBER_URL}/api/v1/auth/login`,
      JSON.stringify({ email: `load-topup-user-${index}@test.com`, password: 'Load1234!' }),
      { headers: { 'Content-Type': 'application/json' } });
    tokens[index] = res.json('data.accessToken');
  }
  return { tokens };
}

function request(data, row) {
  const json = { 'Content-Type': 'application/json' };
  if (PHASE === 'pay') {
    const [orderId, buyerIndex] = row;
    return http.post(`${MARKET_URL}/api/v1/orders/${orderId}/payment`, null,
      { headers: { ...json, Authorization: `Bearer ${data.tokens[buyerIndex]}` }, tags: { name: 'pay' } });
  }
  const [deliveryId, orderId] = row;
  if (PHASE === 'ship') {
    // 송장 번호는 (택배사, 번호)로 유일해야 해서 실행·주문마다 다르게 만든다
    const trackingNumber = `K6${RUN_ID.replace(/[^0-9A-Za-z]/g, '')}${orderId}`.slice(0, 30);
    return http.post(`${DELIVERY_URL}/api/v1/logistics/deliveries/${deliveryId}/ship`,
      JSON.stringify({ courier: 'CJ', trackingNumber }),
      { headers: { ...json, 'X-Member-Id': LOGISTICS_ID, 'X-Member-Role': 'LOGISTICS' }, tags: { name: 'ship' } });
  }
  return http.post(`${DELIVERY_URL}/api/v1/admin/deliveries/${deliveryId}/complete`,
    JSON.stringify({ deliveredAt: kstNow(), reason: `k6 ${RUN_ID}` }),
    { headers: { ...json, 'X-Member-Id': ADMIN_ID, 'X-Member-Role': 'ADMIN' }, tags: { name: 'complete' } });
}

// 배송 완료 시각은 KST LocalDateTime(초 단위)이고, 미래거나 송장 등록 시각보다 이르면 안 된다
// 초 아래를 버리므로 run.sh가 송장 등록 뒤 1초 넘게 기다린 다음 부른다
function kstNow() {
  return new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 19);
}

export default function (data) {
  const row = rows[exec.scenario.iterationInTest];
  const res = request(data, row);

  let resultCode = null;
  try {
    resultCode = res.json('resultCode');
  } catch (e) {
    // 본문이 JSON이 아니면 실패로 셈
  }
  const ok = res.status === 200 && resultCode === '200-1';
  success.add(ok);
  if (ok) successDuration.add(res.timings.duration);
  check(res, { [`${PHASE} 200-1`]: () => ok });
  if (!ok) console.warn(`${PHASE} 실패 ${row[0]}: ${res.status} ${String(res.body).slice(0, 200)}`);
}
