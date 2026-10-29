#!/usr/bin/env bash
set -euo pipefail
project=${1:?Supply the client project ID}
region=${2:-us-central1}
bucket="${project}-logicov-tfstate"
gcloud storage buckets create "gs://$bucket" --project "$project" --location "$region" --uniform-bucket-level-access --public-access-prevention
gcloud storage buckets update "gs://$bucket" --versioning
printf 'State bucket: gs://%s\n' "$bucket"
