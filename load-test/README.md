# Rate Limiter Load Test — JMeter

# Target: prove ≥ 10,000 requests/second against the default window.
#
# Usage:
#   jmeter -n -t rate-limiter-load.jmx -l results.jtl
#
# The script:
#   1. Starts 200 threads, each ramping up over 2 seconds
#   2. Each thread fires 100 GET /ratelimit/check/{clientId} requests
#      with an X-Api-Key header (resolved to a stable client id)
#   3. Aggregates total throughput; expected: ~10,000+ req/s sustained
#
# To run against a non-default host/port, set:
#   -DtargetHost=... -DtargetPort=...
