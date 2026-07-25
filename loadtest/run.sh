#!/usr/bin/env bash
set -ue

usage() {
  echo "Usage: $0 --server-url <url> --namespace <namespace> --key-name <key> --policy-id <policy> --k8s-secret <secret> --script <sustained.ts|burst.ts>" >&2
}

server_url=""
namespace=""
key_name=""
k8s_secret=""
policy_id=""
script=""
expect=""

for arg in "$@"; do
  case $expect in
    server-url) server_url="$arg"; expect="" ;;
    namespace) namespace="$arg"; expect="" ;;
    key-name) key_name="$arg"; expect="" ;;
    policy-id) policy_id="$arg"; expect="" ;;
    k8s-secret) k8s_secret="$arg"; expect="" ;;
    script) script="$arg"; expect="" ;;
    *)
      case $arg in
        --server-url) expect="server-url" ;;
        --namespace) expect="namespace" ;;
        --key-name) expect="key-name" ;;
        --policy-id) expect="policy-id" ;;
        --k8s-secret) expect="k8s-secret" ;;
        --script) expect="script" ;;
        *)
          echo "Error: unknown argument '$arg'" >&2
          usage
          exit 1
          ;;
      esac
      ;;
  esac
done

if [ -n "$expect" ]; then
  echo "Error: --$expect requires a value" >&2
  usage
  exit 1
fi

errors=0
if [ -z "$server_url" ]; then echo "Error: --server-url is required" >&2; errors=1; fi
if [ -z "$namespace" ]; then echo "Error: --namespace is required" >&2; errors=1; fi
if [ -z "$key_name" ]; then echo "Error: --key-name is required" >&2; errors=1; fi
if [ -z "$policy_id" ]; then echo "Error: --policy-id is required" >&2; errors=1; fi
if [ -z "$k8s_secret" ]; then echo "Error: --k8s-secret is required" >&2; errors=1; fi
if [ -z "$script" ]; then echo "Error: --script is required" >&2; errors=1; fi

if [ $errors -ne 0 ]; then
  usage
  exit 1
fi

if [ ! -f "$script" ]; then
  echo "Error: script '$script' not found" >&2
  exit 1
fi

api_key=$(kubectl -n "$namespace" get secret "$k8s_secret" -o jsonpath='{.data.api-key}' | base64 -d)
docker run --rm -u "$(id -u)" -e API_KEY="$api_key" -e POLICY_ID="$policy_id" -e KEY_NAME="$key_name" -e SLUICE_URL="$server_url" -v "$PWD:/app" -w /app grafana/k6 run "$script"
