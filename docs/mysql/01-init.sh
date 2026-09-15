# Sourced by the official MySQL entrypoint only when initializing an empty volume.
# The legacy dump contains zero dates; relax only this import session.
{
  printf '%s\n' "SET SESSION sql_mode = REPLACE(REPLACE(@@SESSION.sql_mode, 'NO_ZERO_IN_DATE', ''), 'NO_ZERO_DATE', '');"
  cat /opt/hmdp.sql
} | docker_process_sql --database="$MYSQL_DATABASE"
