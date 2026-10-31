#!/bin/sh
# Initializes the persistent data directory (Railway volume mounted at DATA_DIR), then starts the app.
set -e

DATA_DIR="${DATA_DIR:-/data}"
SEED_DIR="/app/seed-data"
export DATA_DIR

mkdir -p "$DATA_DIR"

# 1. Seed defaults only for files that do not exist yet (never overwrite accumulated data).
if [ -d "$SEED_DIR" ]; then
  cp -rn "$SEED_DIR"/. "$DATA_DIR"/ 2>/dev/null || true
fi

# 2. Cache directories
for d in finder-cache weekly-cache analysts-cache alpha-agent-cache monitoring-cache \
         monitoring-data institutional-flow-cache analyses; do
  mkdir -p "$DATA_DIR/$d"
done
mkdir -p "$DATA_DIR/monitoring"

# 3. Some code reads these relative to the working directory (/app), not DATA_DIR.
#    Keep the real files/dirs on the volume and symlink them into /app.
for f in trading-config.json scoring-config.json swing-variants.json momentum-variants.json \
         intraday-variants.json momentum-success-2026.json daily-trading-sim.json real-trading.json \
         api-usage-history.json favorites.txt portfolio.txt monitoring-stocks.txt; do
  [ -e "$DATA_DIR/$f" ] || [ ! -f "/app/$f" ] || [ -L "/app/$f" ] || cp "/app/$f" "$DATA_DIR/$f"
  [ -e "$DATA_DIR/$f" ] || continue
  ln -sfn "$DATA_DIR/$f" "/app/$f"
done
for d in analyses monitoring-cache; do
  rm -rf "/app/$d"
  ln -sfn "$DATA_DIR/$d" "/app/$d"
done

echo "DATA_DIR=$DATA_DIR ready"
exec java -jar /app/app.jar
