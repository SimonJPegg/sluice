const http = require('k6/http');
const k6 = require('k6');

module.exports.options = {
  scenarios: {
    burst: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { duration: '1m', target: 100 },   // baseline at 100 req/s
        { duration: '10s', target: 500 },   // spike to 500 req/s
        { duration: '30s', target: 500 },   // hold the burst
        { duration: '10s', target: 100 },   // drop back
        { duration: '1m', target: 100 },    // recovery
      ],
    },
  },
};


module.exports.default = function () {

  const apikey = __ENV.API_KEY;
  const url = __ENV.SLUICE_URL;
  const keyName = __ENV.KEY_NAME;
  const policy = __ENV.POLICY_ID;

  const body = {
    key: keyName,
    policyId: policy
  }

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'Authorization': `Bearer ${apikey}`,
    }
  }

  const result = http.post(url, JSON.stringify(body), params);
  k6.check(result, {
    'allowed or denied': (r) => r.status === 200 || r.status === 429,
  });
};
