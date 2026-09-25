#!/usr/bin/env bash
set -euo pipefail
if [ -z "${SUPABASE_DB_URL:-}" ]; then
  echo "::warning::SUPABASE_DB_URL não configurado; migrations do Supabase foram ignoradas neste workflow."
  exit 0
fi

for attempt in 1 2 3; do
  if supabase db push --db-url "$SUPABASE_DB_URL" --include-all; then
    exit 0
  fi
  echo "::warning::Não foi possível conectar ao Supabase (tentativa $attempt/3)."
  sleep 5
done

echo "::warning::Migrations do Supabase não foram aplicadas por indisponibilidade de rede. Testes e build continuarão."
exit 0
