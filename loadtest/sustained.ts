const http = require('k6/http');
const k6 = require('k6');

module.exports.options = {
  scenarios: {
    sustained: {
      executor: 'constant-arrival-rate',
      rate: 500,
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: 50,
    }
  }
}

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
