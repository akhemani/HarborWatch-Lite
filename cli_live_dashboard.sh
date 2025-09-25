#!/usr/bin/env bash
# HarborWatch menu demo (one script for everything)
# Options:
#   1) Low CPU
#   2) High CPU
#   3) Memory
#   4) Database
#   5) Combined (heavy)
#   6) Reset metrics (restart app)
#   7) Reset metrics + wipe DB tables
#   0) Exit
#
# After each run we append:
#   - App metrics (/actuator/prometheus): process CPU + http_server_requests for that endpoint
#   - Host metrics (node_exporter): a few CPU counters + MemAvailable
#   - Last 8 app logs
# No screen clears; output appends so you can compare runs easily.

APP_URL="http://localhost:8080"
PROM="${APP_URL}/actuator/prometheus"
NODE="http://localhost:9100/metrics"
CADVISOR_URL="http://localhost:8085"
APP_CONT="hw-app"
DB_CONT="hw-db"
DB_USER="postgres"
DB_NAME="appdb"

line() { printf '%*s\n' "${COLUMNS:-80}" '' | tr ' ' -; }
ts()   { date "+%Y-%m-%d %H:%M:%S %Z"; }

show_app_metrics() {
  echo
  echo "[APP metrics] /actuator/prometheus"

  # 1) Process CPU (fraction of 1 core)
  curl -s "${PROM}" | awk '
    /^process_cpu_usage/ {print; f=1}
    END { if (!f) print "(no app metrics yet)"}'

  # 2) Aggregate request metrics for the chosen URI across all time series
  #    - Sum all "count" and "sum"
  #    - Take the max of "max"
  #    - Compute avg = sum / count
  local uri="$1"               # e.g. /api/cpu-intensive or /api/combined-stress
  local re='uri="'$uri'"'

  local CNT SUM MAX
  CNT=$(curl -s "${PROM}" | awk -v re="$re" '
    /^http_server_requests_seconds_count/ && $0 ~ re {c += $2}
    END { printf "%.0f\n", (c ? c : 0) }')

  SUM=$(curl -s "${PROM}" | awk -v re="$re" '
    /^http_server_requests_seconds_sum/ && $0 ~ re {s += $2}
    END { printf "%.6f\n", (s ? s : 0) }')

  MAX=$(curl -s "${PROM}" | awk -v re="$re" '
    /^http_server_requests_seconds_max/ && $0 ~ re { if ($2 > m) m = $2 }
    END { if (m == "") m = 0; printf "%.6f\n", m }')

  echo "http_server_requests_seconds_count_total  ${CNT}"
  echo "http_server_requests_seconds_sum_total    ${SUM}"
  echo "http_server_requests_seconds_max_seen     ${MAX}"

  if [ "${CNT}" != "0" ]; then
    awk -v s="${SUM}" -v c="${CNT}" \
      'BEGIN { printf "avg_seconds (sum/count)               %.6f\n", (c > 0 ? s/c : 0) }'
  else
    echo "avg_seconds (sum/count)               0.000000"
  fi

  # 3) OPTIONAL: per-status breakdown (uncomment to show counts by HTTP status)
  # echo
  # echo "By status (count):"
  # curl -s "${PROM}" | awk -v re="$re" '
  #   /^http_server_requests_seconds_count/ && $0 ~ re {
  #     if (match($0, /status="([0-9]{3})"/, m)) by[m[1]] += $2
  #   }
  #   END {
  #     for (s in by) printf "  status=%s  count=%.0f\n", s, by[s]
  #   }' | sort
}

show_host_metrics() {
  echo
  echo "[HOST metrics] node_exporter :9100/metrics"

  # Grab all metrics once so we parse a consistent snapshot
  MET="$(curl -s "${NODE}")"

  # Discover CPU ids (cpu="0","1",...) and cap how many we print to keep output readable.
  MAX_CPUS=${MAX_CPUS:-16}  # set MAX_CPUS=999 when running to print all
  CPU_IDS=$(echo "${MET}" \
    | grep '^node_cpu_seconds_total{' \
    | grep -o 'cpu="[0-9]\+"' \
    | sed 's/cpu="//; s/"//' \
    | sort -n | uniq | head -n "${MAX_CPUS}")

  # Helper: get value for a given cpu & mode using plain grep
  getval() {
    # usage: getval <cpu_id> <mode>
    local cpu="$1" mode="$2"
    echo "${MET}" \
      | grep "^node_cpu_seconds_total{cpu=\"${cpu}\",mode=\"${mode}\"}" \
      | awk '{print $2}' \
      | head -n 1
  }

  # Print one tidy line per CPU
  for cpu in ${CPU_IDS}; do
    idle="$(getval "${cpu}" idle)";     [ -z "${idle}" ] && idle=0
    iow="$(getval "${cpu}" iowait)";    [ -z "${iow}" ]  && iow=0
    irq="$(getval "${cpu}" irq)";       [ -z "${irq}" ]  && irq=0
    nice="$(getval "${cpu}" nice)";     [ -z "${nice}" ] && nice=0
    soft="$(getval "${cpu}" softirq)";  [ -z "${soft}" ] && soft=0
    echo "cpu ${cpu}: idle=${idle}s, iowait=${iow}s, irq=${irq}s, nice=${nice}s, softirq=${soft}s"
  done

  # MemAvailable (bytes) → GiB (2 decimals)
  mem_bytes=$(echo "${MET}" | grep '^node_memory_MemAvailable_bytes ' | awk '{print $2}' | head -n 1)
  [ -z "${mem_bytes}" ] && mem_bytes=0
  mem_gib=$(awk -v b="${mem_bytes}" 'BEGIN{printf("%.2f", b/1024/1024/1024)}')
  echo "MemAvailable ≈ ${mem_gib} GiB"

  # If we truncated, tell the user
  total_cpus=$(echo "${MET}" | grep '^node_cpu_seconds_total{' | grep -o 'cpu="[0-9]\+"' | wc -l)
  total_cpus=$(( total_cpus > 0 ? total_cpus : 0 ))
  unique_cpus=$(echo "${CPU_IDS}" | wc -w | tr -d ' ')
  if [ "${total_cpus}" -gt "${unique_cpus}" ]; then
    echo "(showing ${unique_cpus} of ${total_cpus} CPUs; run with MAX_CPUS=999 to show all)"
  fi
}

show_logs() {
  echo
  echo "[APP logs] (last 8 lines)"
  docker logs --tail=8 "${APP_CONT}" 2>/dev/null || echo "(app not ready)"
}

burst() {
  # Hit a URL N times with small delay; prints simple progress dots.
  local url="$1"; local times="$2"; local delay="${3:-0.2}"
  echo "Hitting: ${url}   (${times}x)"
  for i in $(seq 1 "${times}"); do
    curl -s "${url}" >/dev/null || true
    printf '.'
    sleep "${delay}"
  done
  echo
}

run_low_cpu() {
  local uri="/api/cpu-intensive"
  echo; line
  echo "[$(ts)] Option 1: LOW CPU"; line
  burst "${APP_URL}${uri}?iterations=200000" 15 0.2
  show_app_metrics "${uri}"
  show_host_metrics
  show_logs
  echo
  echo "Tip: open cAdvisor ${CADVISOR_URL} and click the hw-app container."
}

run_high_cpu() {
  local uri="/api/cpu-intensive"
  echo; line
  echo "[$(ts)] Option 2: HIGH CPU"; line
  burst "${APP_URL}${uri}?iterations=5000000" 12 0.1
  show_app_metrics "${uri}"
  show_host_metrics
  show_logs
}

run_memory() {
  local uri="/api/memory-intensive"
  echo; line
  echo "[$(ts)] Option 3: MEMORY"; line
  burst "${APP_URL}${uri}?sizeMb=100" 6 0.5
  show_app_metrics "${uri}"
  show_host_metrics
  show_logs
}

run_db() {
  local uri="/api/database-intensive"
  echo; line
  echo "[$(ts)] Option 4: DB"; line
  burst "${APP_URL}${uri}?ops=800" 3 0.7
  show_app_metrics "${uri}"
  show_host_metrics
  show_logs
}

run_combined() {
  # Very heavy combined:
  # - Two parallel /api/combined-stress runs (20s each)
  # - Interleaved CPU and Memory bursts in parallel while they run
  local comb="/api/combined-stress"
  local cpu="/api/cpu-intensive?iterations=7000000"
  local mem="/api/memory-intensive?sizeMb=150"

  echo; line
  echo "[$(ts)] Option 5: COMBINED (heavy: CPU + Memory + DB in parallel)"; line

  echo "Launching heavy combined runs in background..."
  curl -s "${APP_URL}${comb}?durationSec=20" >/dev/null & p1=$!
  curl -s "${APP_URL}${comb}?durationSec=20" >/dev/null & p2=$!

  echo "Adding parallel CPU & Memory bursts while combined runs execute..."
  for i in {1..6}; do
    curl -s "${APP_URL}${cpu}" >/dev/null &
    curl -s "${APP_URL}${mem}" >/dev/null &
    sleep 1
  done

  echo "Waiting for combined runs to finish..."
  wait $p1 $p2 || true
  echo "Combined heavy round completed."

  show_app_metrics "${comb}"
  show_host_metrics
  show_logs
}

reset_metrics_only() {
  echo; line
  echo "[$(ts)] Option 6: Reset metrics (restart app)"; line
  echo "Restarting ${APP_CONT}..."
  docker restart "${APP_CONT}" >/dev/null || true
  echo "App restarted. /actuator/prometheus counters reset."
  echo "Note: DB data is unchanged."
}

reset_metrics_and_db() {
  echo; line
  echo "[$(ts)] Option 7: Reset metrics + wipe DB tables"; line
  echo "Restarting ${APP_CONT}..."
  docker restart "${APP_CONT}" >/dev/null || true
  echo "Truncating tables in ${DB_NAME}..."
  docker exec -i "${DB_CONT}" psql -U "${DB_USER}" -d "${DB_NAME}" <<'SQL'
TRUNCATE TABLE performance_data RESTART IDENTITY;
TRUNCATE TABLE computation_results RESTART IDENTITY;
SQL
  echo "Done. Metrics reset and DB tables cleared."
}

# --- menu loop ---
while true; do
  echo
  echo "HarborWatch menu — pick a test and press Enter (output appends below):"
  echo "  1) Low CPU"
  echo "  2) High CPU"
  echo "  3) Memory"
  echo "  4) Database"
  echo "  5) Combined (heavy)"
  echo "  6) Reset metrics (restart app)"
  echo "  7) Reset metrics + wipe DB tables"
  echo "  0) Exit"
  read -r -p "Your choice: " choice
  case "${choice}" in
    1) run_low_cpu ;;
    2) run_high_cpu ;;
    3) run_memory ;;
    4) run_db ;;
    5) run_combined ;;
    6) reset_metrics_only ;;
    7) reset_metrics_and_db ;;
    0) echo "Bye."; exit 0 ;;
    *) echo "Unknown option. Try again." ;;
  esac
  echo
  read -r -p "Press Enter for menu..." _
done
