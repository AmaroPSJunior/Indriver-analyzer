#!/usr/bin/env bash
set -euo pipefail
: "${SUPABASE_DB_URL:?Configure SUPABASE_DB_URL in GitHub Actions secrets}"
supabase db push --db-url "$SUPABASE_DB_URL" --include-all
