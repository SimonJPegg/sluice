const http = require('k6/http');
const k6 = require('k6');

module.exports.options = {
  scenarios: {
    burst: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 200,
      maxVUs: 1000,
      stages: [
        { duration: '30s', target: 5000 },   // warm up
        { duration: '10s', target: 20000 },  // spike
        { duration: '30s', target: 20000 },  // hold
        { duration: '10s', target: 5000 },   // drop back
        { duration: '30s', target: 1000 },   // recovery
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
