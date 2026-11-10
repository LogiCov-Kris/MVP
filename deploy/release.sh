#!/usr/bin/env bash
set -euo pipefail
: "${GCP_PROJECT_ID:?}" "${GCP_REGION:?}" "${GCP_SERVICE:?}"
image=${1:?Supply an image digest}
[[ "$image" =~ @sha256:[a-f0-9]{64}$ ]] || { echo 'An immutable image digest is required' >&2; exit 1; }
gcloud run services describe "$GCP_SERVICE" --project "$GCP_PROJECT_ID" --region "$GCP_REGION" >/dev/null
revision=$(gcloud run services update "$GCP_SERVICE" --project "$GCP_PROJECT_ID" --region "$GCP_REGION" \
  --image "$image" --no-traffic --tag candidate --format='value(status.latestCreatedRevisionName)')
url=$(gcloud run services describe "$GCP_SERVICE" --project "$GCP_PROJECT_ID" --region "$GCP_REGION" \
  --flatten='status.traffic' --filter='status.traffic.tag=candidate' --format='value(status.traffic.url)')
[[ "$url" == https://* && "$url" != *$'\n'* ]] || { echo 'Expected one HTTPS candidate URL' >&2; exit 1; }
curl --fail --retry 5 --retry-all-errors --retry-delay 3 "$url/api/ready"
curl --fail "$url/" >/dev/null
status=$(curl --silent --output /dev/null --write-out '%{http_code}' "$url/api/session")
[[ "$status" == 401 ]] || { echo "Unauthenticated session returned $status" >&2; exit 1; }
gcloud run services update-traffic "$GCP_SERVICE" --project "$GCP_PROJECT_ID" --region "$GCP_REGION" --to-revisions "$revision=100"
