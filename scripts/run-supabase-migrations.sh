#!/usr/bin/env bash
set -euo pipefail
if [ -z "${SUPABASE_DB_URL:-}" ]; then
  echo "::warning::SUPABASE_DB_URL não configurado; migrations do Supabase foram ignoradas neste workflow."
  exit 0
fi
supabase db push --db-url "$SUPABASE_DB_URL" --include-all
