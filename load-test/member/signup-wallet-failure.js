// 회원가입 부하 테스트 (지갑 생성 장애 비포·애프터 측정용)
// 측정 순서와 장애 주입 방법은 load-test/README.md 참고
//
// 실행 예:
//   k6 run -e RUN_ID=before-normal -e COUNT=200 -e VUS=10 \
//     --summary-export=load-test/results/member/before-normal.json load-test/member/signup-wallet-failure.js
//
// 환경 변수
//   BASE_URL  가입을 받는 서버 주소 (기본 http://localhost:8081, member-service)
//   RUN_ID    실행 이름. 이메일·인증 토큰에 들어가서 실행마다 다른 회원으로 가입한다 (prepare-signup-tokens.sh와 같은 값)
//   COUNT     가입 요청 수 (prepare-signup-tokens.sh와 같은 값)
//   VUS       동시 사용자 수
//   TIMEOUT   요청 하나의 응답 대기 한도 (기본 60s)
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const RUN_ID = __ENV.RUN_ID;
const COUNT = Number(__ENV.COUNT || 200);
const VUS = Number(__ENV.VUS || 10);
const TIMEOUT = __ENV.TIMEOUT || '60s';

if (!RUN_ID) {
  throw new Error('RUN_ID를 지정해 주세요 (예: -e RUN_ID=before-normal)');
}

// 가입 성공률: HTTP 200이고 resultCode가 200-1인 비율
const signupSuccess = new Rate('signup_success');
// 성공한 가입만의 응답 시간 (실패 응답은 빨리 끝나서 전체 p95를 낮춰 보이게 하므로 따로 본다)
const signupSuccessDuration = new Trend('signup_success_duration', true);

export const options = {
  scenarios: {
    signup: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: COUNT,
      maxDuration: '10m',
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  // 0부터 COUNT-1까지 겹치지 않는 번호. 토큰·이메일을 이 번호로 만든다
  const i = exec.scenario.iterationInTest;
  const token = `load-${RUN_ID}-${i}`;
  const email = `load-${RUN_ID}-${i}@test.com`;

  const res = http.post(
    `${BASE_URL}/api/v1/auth/signup`,
    JSON.stringify({ email, password: 'Load1234!', name: '부하테스트', verificationToken: token }),
    { headers: { 'Content-Type': 'application/json' }, timeout: TIMEOUT, tags: { name: 'signup' } },
  );

  let resultCode = null;
  try {
    resultCode = res.json('resultCode');
  } catch (e) {
    // 타임아웃·연결 실패면 본문이 없다
  }
  const ok = res.status === 200 && resultCode === '200-1';

  signupSuccess.add(ok);
  if (ok) signupSuccessDuration.add(res.timings.duration);

  check(res, { 'signup 200-1': () => ok });
}
