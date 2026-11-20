// 충전 부하 테스트 (PG 장애 비포·애프터 측정용)
// 충전 준비 → 가짜 PG로 승인 순서로 호출함. 측정 순서와 장애 주입 방법은 load-test/README.md 참고
//
// 실행 예:
//   k6 run -e RUN_ID=before-normal -e COUNT=200 -e VUS=10 \
//     --summary-export=load-test/results/topup/before-normal.json load-test/topup/topup-pg-failure.js
//
// 환경 변수
//   BASE_URL    충전 API 서버 (기본 http://localhost:8083, payment-service)
//   MEMBER_URL  로그인 서버 (기본 http://localhost:8081, member-service)
//   RUN_ID      실행 이름. paymentKey(fake-{RUN_ID}-{i})에 들어가서 DB·가짜 PG에서 이번 실행만 골라 볼 수 있음
//   COUNT       충전 요청 수
//   VUS         동시 사용자 수
//   USERS       충전에 쓸 회원 수 (기본 10, prepare-topup-users.sh와 같은 값). VU마다 다른 회원을 써서 지갑 잠금이 겹치지 않게 함
//   USER_RUN_ID 충전용 회원의 RUN_ID (기본 topup-user → load-topup-user-{i}@test.com)
//   TIMEOUT     요청 하나의 응답 대기 한도 (기본 60s)
import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8083';
const MEMBER_URL = __ENV.MEMBER_URL || 'http://localhost:8081';
const RUN_ID = __ENV.RUN_ID;
const COUNT = Number(__ENV.COUNT || 200);
const VUS = Number(__ENV.VUS || 10);
const USERS = Number(__ENV.USERS || 10);
const USER_RUN_ID = __ENV.USER_RUN_ID || 'topup-user';
const TIMEOUT = __ENV.TIMEOUT || '60s';
const PASSWORD = 'Load1234!';
const AMOUNT = 10000;

if (!RUN_ID) {
  throw new Error('RUN_ID를 지정해 주세요 (예: -e RUN_ID=before-normal)');
}

// 승인 응답의 data.status로 나눔. 셋을 더하면 100%
const topupDone = new Rate('topup_done');             // 바로 충전 완료(DONE)
const topupProcessing = new Rate('topup_processing'); // 결과 모름(PROCESSING) → 대사 스케줄러가 나중에 확정
const topupFailed = new Rate('topup_failed');         // 오류 응답·타임아웃
// 충전 완료(DONE)된 승인 요청만의 응답 시간
const topupDoneDuration = new Trend('topup_done_duration', true);
// 충전 준비가 고른 PG별 건수와, 둘 다 사용 불가라 충전 준비가 503인 건수 (애프터: 서버가 PG를 고름)
const pgToss = new Counter('topup_pg_toss');
const pgHecto = new Counter('topup_pg_hecto');
const pgUnavailable = new Counter('topup_pg_unavailable');

export const options = {
  scenarios: {
    topup: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: COUNT,
      maxDuration: '20m',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  // 승인 요청만의 응답 시간을 요약에 따로 보이게 하려고 거는 형식상의 기준 (항상 통과)
  thresholds: {
    'http_req_duration{name:confirm}': ['max>=0'],
  },
};

// 충전용 회원으로 로그인해 access token을 모아 둠 (측정 전에 한 번)
export function setup() {
  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    const res = http.post(
      `${MEMBER_URL}/api/v1/auth/login`,
      JSON.stringify({ email: `load-${USER_RUN_ID}-${i}@test.com`, password: PASSWORD }),
      { headers: { 'Content-Type': 'application/json' } },
    );
    if (res.status !== 200) {
      fail(`로그인 실패 (회원 ${i}, HTTP ${res.status}). prepare-topup-users.sh를 먼저 실행했는지 확인`);
    }
    tokens.push(res.json('data.accessToken'));
  }
  return { tokens };
}

export default function (data) {
  const i = exec.scenario.iterationInTest;
  const token = data.tokens[(exec.vu.idInTest - 1) % data.tokens.length];
  const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };

  // 1. 충전 준비 (PG를 부르지 않음)
  const prepared = http.post(`${BASE_URL}/api/v1/topups`, JSON.stringify({ amount: AMOUNT }),
    { headers, timeout: TIMEOUT, tags: { name: 'prepare' } });
  if (prepared.status === 503) pgUnavailable.add(1);
  if (prepared.status !== 200) {
    topupFailed.add(true);
    topupDone.add(false);
    topupProcessing.add(false);
    check(prepared, { 'prepare 200': () => false });
    return;
  }
  const orderId = prepared.json('data.orderId');
  // 비포 커밋의 응답에는 pgProvider가 없음 → 토스로 셈
  if (prepared.json('data.pgProvider') === 'HECTO') pgHecto.add(1); else pgToss.add(1);

  // 2. 승인 (가짜 PG 호출). 결제창을 거치지 않으므로 paymentKey는 아무 값이나 씀 (헥토는 이 값을 인증 거래번호로 보냄)
  const res = http.post(`${BASE_URL}/api/v1/topups/confirm`,
    JSON.stringify({ paymentKey: `fake-${RUN_ID}-${i}`, orderId, amount: AMOUNT }),
    { headers, timeout: TIMEOUT, tags: { name: 'confirm' } });

  let status = null;
  try {
    status = res.json('data.status');
  } catch (e) {
    // 타임아웃·연결 실패면 본문이 없음
  }
  const done = res.status === 200 && status === 'DONE';
  const processing = res.status === 200 && status === 'PROCESSING';

  topupDone.add(done);
  topupProcessing.add(processing);
  topupFailed.add(!done && !processing);
  if (done) topupDoneDuration.add(res.timings.duration);

  check(res, { 'confirm DONE': () => done });
}
