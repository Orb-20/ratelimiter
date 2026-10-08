# Distributed Rate Limiter (Spring Boot + Redis)

A small, production-style project showing how rate limiting evolves in three steps:

| # | Implementation | State | Endpoint |
|---|----------------|-------|----------|
| 1 | Fixed Window Counter | in-memory (`ConcurrentHashMap`) | `GET /api/rate-limit/counter` |
| 2 | Token Bucket (lazy refill) | in-memory (`ConcurrentHashMap`, per-user lock) | `GET /api/rate-limit/token-bucket` |
| 3 | Distributed Token Bucket | Redis Hash + atomic Lua script | `GET /api/rate-limit/redis` |

`GET /api/rate-limit/test` uses the algorithm set in `rate-limit.default-algorithm` (default `TOKEN_BUCKET`).

Every endpoint identifies the caller with the `X-User-Id` header.

---

## Contents

1. [What is rate limiting?](#1-what-is-rate-limiting)
2. [Why is it needed?](#2-why-is-rate-limiting-needed)
3. [Fixed Window Counter](#3-fixed-window-counter)
4. [The fixed-window boundary problem](#4-the-fixed-window-boundary-problem)
5. [Token Bucket](#5-token-bucket)
6. [Lazy token refill](#6-lazy-token-refill)
7. [Why in-memory state fails with multiple servers](#7-why-in-memory-state-fails-with-multiple-servers)
8. [Why Redis?](#8-why-redis)
9. [Redis is shared state, not the algorithm](#9-redis-is-shared-state-not-the-algorithm)
10. [Redis atomicity](#10-redis-atomicity)
11. [Lua scripts](#11-lua-scripts)
12. [Redis TTL](#12-redis-ttl)
13. [Redis failure strategy](#13-redis-failure-strategy)
14. [Running the application](#14-running-the-application)
15. [Running multiple instances](#15-running-multiple-spring-boot-instances)
16. [Testing with curl](#16-testing-with-curl)
17. [Running the tests](#17-running-the-tests)
18. [Project structure](#18-project-structure)
19. [Interview questions and answers](#19-interview-questions-and-answers)
20. [Interview demonstration script](#20-interview-demonstration-script)

---

## 1. What is rate limiting?

Rate limiting controls **how many requests a client may make in a given period**. Requests over the limit are
rejected, here with **HTTP 429 Too Many Requests**, until the client is allowed to send again.

## 2. Why is rate limiting needed?

- **Protects the service** from overload, whether from traffic spikes, buggy clients or retry storms.
- **Fairness**: one noisy user cannot starve everyone else.
- **Security**: slows brute-force logins, credential stuffing, scraping and simple DoS attempts.
- **Cost control**: caps usage of expensive downstream resources (databases, paid third-party APIs).
- **Business tiers**: free and premium plans get different quotas.

## 3. Fixed Window Counter

`FixedWindowRateLimiter`: **100 requests per 60-second window**.

Time is cut into fixed windows aligned to the clock (`10:00:00–10:00:59`, `10:01:00–10:01:59`, …).
For each user we keep `windowStartTime` and `requestCount`.

```text
Request
   |
   v
Find user counter  (ConcurrentHashMap.compute -> atomic per key)
   |
   v
Has window expired? --Yes--> reset counter to 1
   |
   No --> increment counter
                |
                v
          count <= 100?
           /        \
         Yes         No
          |           |
        Allow     429 + Retry-After (seconds until the window ends)
```

Thread safety: `ConcurrentHashMap.compute()` runs the "reset or increment" step atomically **per key**,
so concurrent requests for one user cannot lose updates, and different users don't block each other.

## 4. The fixed-window boundary problem

```text
         window 10:00                     window 10:01
|---------------------------------|---------------------------------|
                          100 req ^ 100 req
                         10:00:59 | 10:01:00
                                  ^ boundary
```

Each window individually respects "100/minute", yet **200 requests pass within about one second**.
The test `FixedWindowRateLimiterTest.fixedWindowBoundaryProblem` demonstrates exactly this.

## 5. Token Bucket

`TokenBucketRateLimiter`: **capacity 100, refill 10 tokens/second**.

```text
            refill: +10 tokens / second
                      |
                      v
              +---------------+
              |  ● ● ● ● ● ●  |  capacity = 100 (max burst)
              |  ● ● ● ● ● ●  |
              +-------+-------+
                      |
         each request takes 1 token
                      |
          token available?  yes -> allow, tokens--
                            no  -> 429, Retry-After = time until 1 token refills
```

- **Bursts** up to `capacity` are allowed.
- The **long-term rate** is capped at `refillRate`.
- No boundary problem: after a burst of 100 the user must wait for tokens to come back.
  Test `TokenBucketRateLimiterTest.noBoundaryBurst` shows that 1 second after a burst of 100, only 10 more are allowed.

## 6. Lazy token refill

There is **no background thread per user**. Refill is computed when a request arrives:

```text
elapsed   = now - lastRefillTime
newTokens = elapsed × refillRate
tokens    = min(capacity, tokens + newTokens)
lastRefillTime = now
then: if tokens >= 1 -> tokens -= 1, allow; else reject
```

Fractional tokens are kept (stored as `double`), so slow refill rates still accumulate correctly.
Each user's `BucketState` is its own lock, so the whole application is never synchronized at once.

## 7. Why in-memory state fails with multiple servers

```text
                 Client (alice)
                       |
                 Load Balancer
               /       |       \
         Server1    Server2    Server3
        bucket=100 bucket=100 bucket=100   <- each JVM has its own map
```

With N servers behind a round-robin load balancer, alice effectively gets **N × 100** requests,
because each instance only sees part of her traffic. Restarting a server also resets its buckets.

## 8. Why Redis?

```text
                         Client
                           |
                           v
                    Load Balancer
                     /     |     \
                    v      v      v
                 Server1 Server2 Server3
                    \      |      /
                     \     |     /
                       Redis
                         |
           rate-limit:token-bucket:alice  (one shared bucket)
```

- **Shared**: every instance reads and writes the same bucket.
- **Fast**: in-memory, with sub-millisecond operations, so it fits on the request path of every API call.
- **Atomic**: single-threaded command execution plus Lua scripts give race-free check-and-consume.
- **TTL**: built-in key expiry cleans up inactive users.

## 9. Redis is shared state, not the algorithm

Redis does not "rate limit" anything by itself. **The algorithm is still the token bucket**, the same
math as implementation 2. Redis is the **shared state and coordination layer** that makes all
application instances see one bucket per user. The algorithm lives in `token_bucket.lua`.

Key design: a Redis **Hash** per user

```text
rate-limit:token-bucket:{userId}
    tokens      -> 42.5
    lastRefill  -> 1759920000000   (epoch millis, Redis server time)
```

## 10. Redis atomicity

The naive approach is **wrong**:

```text
GET  bucket         <- Server 1 reads tokens=1
GET  bucket         <- Server 2 reads tokens=1
calculate           <- both think a token is available
SET  tokens=0       <- Server 1 allows
SET  tokens=0       <- Server 2 allows   => 2 requests consumed 1 token
```

Read, calculate and write must happen **as one indivisible step**.
Test `RedisTokenBucketRateLimiterIntegrationTest.concurrentRequestsConsumeExactlyCapacityTokens`
fires 500 concurrent requests at a bucket of 50 and asserts **exactly 50** are allowed.

## 11. Lua scripts

`src/main/resources/scripts/token_bucket.lua` runs inside Redis. Redis executes a script **atomically**:
no other command runs while it does. The script:

1. Reads `tokens` and `lastRefill` (`HMGET`).
2. Initializes a full bucket for a new user.
3. Calculates elapsed time.
4. Calculates the refill.
5. Caps tokens at capacity.
6. Checks whether a token is available.
7. Consumes one token if so.
8. Writes the state back (`HSET`) and refreshes the TTL (`EXPIRE`).
9. Returns `{allowed, remainingTokens, retryAfterMillis}`.

Design choices:

- **Redis server time (`TIME`)** is used instead of each app server's clock, so clock skew between
  application servers cannot create extra tokens. All time values are in milliseconds.
- Capacity, refill rate and TTL are **passed as arguments**, not hardcoded, so per-user plans work.
- Spring sends the script with `EVALSHA` (falling back to `EVAL`), so the body isn't resent on each call.
- Integers are returned because Redis truncates Lua floats in replies; fractional tokens stay stored in the hash.

## 12. Redis TTL

Each request runs `EXPIRE key ttl-seconds` (default **3600**), which refreshes the TTL.
A user inactive for an hour has their key removed automatically, so abandoned users do not pile up in Redis.
Losing the key is harmless: a missing bucket is recreated **full**, which is exactly what an idle bucket
would have refilled to anyway (as long as TTL ≥ capacity / refillRate, here 10 s).

## 13. Redis failure strategy

```properties
rate-limit.redis.fail-open=false
```

| Setting | Redis down → | Favors | Risk |
|---------|-------------|--------|------|
| `fail-open=true` | request **allowed** | availability: the API keeps working | no limit enforced during the outage |
| `fail-open=false` (default) | request **rejected** (429, `Retry-After: 1`) | strict enforcement and protection of the backend | outage of Redis = outage of the API |

Failures are never swallowed silently: every Redis failure is logged at `ERROR` with the policy applied, e.g.

```text
Redis unavailable for user=alice - fail-open=false so request is REJECTED: Unable to connect to Redis
```

and `/actuator/health` reports `redis: DOWN`. Short timeouts (`spring.data.redis.timeout=2s`) keep a slow
Redis from tying up request threads.
Typical choice: fail-open for general APIs, fail-closed for security-sensitive endpoints such as login or OTP.

---

## 14. Running the application

### Prerequisites

- Java 17+ (`java -version`)
- Maven 3.8+ (`mvn -v`)
- A local Redis on `localhost:6379`. No Docker is needed:
  - **Linux**: `sudo apt install redis-server && redis-server`
  - **macOS**: `brew install redis && redis-server`
  - **Windows**: install [Memurai](https://www.memurai.com/) (Redis-compatible), *or* unzip the portable
    build [tporadowski/redis](https://github.com/tporadowski/redis/releases) and run `redis-server.exe`,
    *or* `sudo apt install redis-server` inside WSL.
  - Check with: `redis-cli ping` → `PONG`

### Start

```bash
mvn spring-boot:run
```

or build a jar:

```bash
mvn clean package
java -jar target/distributed-rate-limiter-1.0.0.jar
```

Health check (includes Redis connectivity):

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP","components":{...,"redis":{"status":"UP","details":{"ping":"PONG"}}}}
```

### Configuration (`application.properties`)

```properties
server.port=8080
rate-limit.default-algorithm=TOKEN_BUCKET

rate-limit.counter.max-requests=100      # fixed window: 100 requests ...
rate-limit.counter.window-seconds=60     # ... per 60 seconds

rate-limit.token-bucket.capacity=100     # bucket size (max burst)
rate-limit.token-bucket.refill-rate=10   # tokens per second

spring.data.redis.host=localhost
spring.data.redis.port=6379
rate-limit.redis.ttl-seconds=3600
rate-limit.redis.fail-open=false
```

Any property can be overridden on the command line, e.g. `--rate-limit.token-bucket.refill-rate=0.5`.

## 15. Running multiple Spring Boot instances

Open three terminals (all connect to the same local Redis):

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8080"
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8082"
```

Or, after `mvn clean package`:

```bash
java -jar target/distributed-rate-limiter-1.0.0.jar --server.port=8080
java -jar target/distributed-rate-limiter-1.0.0.jar --server.port=8081
java -jar target/distributed-rate-limiter-1.0.0.jar --server.port=8082
```

> **Demo tip:** with the default 10 tokens/s refill, a sequential curl loop is slow enough that refill keeps up,
> so you may never see a 429. For the demo, start all instances with a slow refill:
> `--rate-limit.token-bucket.refill-rate=0.5` (for `mvn spring-boot:run`, use
> `-Dspring-boot.run.arguments="--server.port=8080 --rate-limit.token-bucket.refill-rate=0.5"`).

Each response carries an `X-Server-Port` header, so you can see which instance answered.

## 16. Testing with curl

### Single request

```bash
curl -i -H "X-User-Id: user123" http://localhost:8080/api/rate-limit/test
```

```text
HTTP/1.1 200
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 99
X-Server-Port: 8080

{"allowed":true,"algorithm":"TOKEN_BUCKET","limit":100,"remaining":99}
```

Rejected:

```text
HTTP/1.1 429
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
Retry-After: 1

{"allowed":false,"algorithm":"REDIS_TOKEN_BUCKET","limit":100,"remaining":0,"retryAfterSeconds":1}
```

### Fixed window: hit the limit (bash / Git Bash)

```bash
for i in $(seq 1 101); do
  curl -s -o /dev/null -w "%{http_code}\n" -H "X-User-Id: alice" http://localhost:8080/api/rate-limit/counter
done | sort | uniq -c
#   100 200
#     1 429
```

### Token bucket: consume and refill

```bash
for i in $(seq 1 105); do
  curl -s -o /dev/null -w "%{http_code}\n" -H "X-User-Id: bob" http://localhost:8080/api/rate-limit/token-bucket
done | sort | uniq -c
sleep 2      # ~20 tokens come back at 10/s
curl -s -H "X-User-Id: bob" http://localhost:8080/api/rate-limit/token-bucket
```

### Three servers, one Redis bucket

```bash
# requests rotate across 8080 -> 8081 -> 8082; "remaining" keeps falling across servers
for i in $(seq 0 5); do
  curl -s -H "X-User-Id: alice" http://localhost:$((8080 + i % 3))/api/rate-limit/redis; echo
done

# Drain: about 100 requests are allowed in total, NOT 100 per server
for i in $(seq 1 120); do
  curl -s -o /dev/null -w "%{http_code}\n" -H "X-User-Id: alice" http://localhost:$((8080 + i % 3))/api/rate-limit/redis
done | sort | uniq -c

# Compare with in-memory: each server allows ~100 on its own => ~300 in total
for p in 8080 8081 8082; do
  for i in $(seq 1 100); do curl -s -o /dev/null -H "X-User-Id: erin" http://localhost:$p/api/rate-limit/token-bucket; done
  curl -s -o /dev/null -w "port=$p status=%{http_code}\n" -H "X-User-Id: erin" http://localhost:$p/api/rate-limit/token-bucket
done
```

PowerShell equivalent:

```powershell
0..5 | ForEach-Object {
  $port = 8080 + ($_ % 3)
  (Invoke-WebRequest -UseBasicParsing -Headers @{ "X-User-Id" = "alice" } "http://localhost:$port/api/rate-limit/redis").Content
}
```

### Inspect the bucket in Redis

```bash
redis-cli HGETALL rate-limit:token-bucket:alice
redis-cli TTL     rate-limit:token-bucket:alice
```

### See the failure policy

Stop Redis (`redis-cli shutdown nosave`), then:

```bash
curl -i -H "X-User-Id: alice" http://localhost:8080/api/rate-limit/redis   # 429 with fail-open=false
curl http://localhost:8080/actuator/health                                  # redis: DOWN
```

Restart with `--rate-limit.redis.fail-open=true` and the same request returns 200.

## 17. Running the tests

```bash
mvn clean test
```

| Test class | Type | Covers |
|-----------|------|--------|
| `FixedWindowRateLimiterTest` | unit (controllable clock) | first request, 100 allowed, 101st rejected, window reset, independent users, **boundary problem**, concurrency |
| `TokenBucketRateLimiterTest` | unit (Mockito plan resolver) | initial capacity, consumption, empty → reject, refill, capacity cap, no boundary burst, independent users, per-user plans, concurrency |
| `RedisTokenBucketRateLimiterTest` | unit (Mockito Redis) | key format, script arguments, result mapping, Retry-After, fail-open / fail-closed |
| `RedisTokenBucketRateLimiterIntegrationTest` | integration (local Redis) | connection, initialization, consumption, refill, capacity, 429, multiple users, TTL, **atomic concurrent requests**, **two "servers" sharing one bucket** |
| `RateLimiterControllerTest` | MockMvc | 200/429 + headers for counter and token bucket, default endpoint, 400 on bad user id, fail-closed with Redis unreachable |
| `RedisEndpointIntegrationTest` | MockMvc + local Redis | 429 and headers on `/redis` |

Redis integration tests **assume Redis is running on localhost:6379**. If it isn't, they are reported as
*skipped* instead of failing (via `@EnabledIf(RedisAvailability#isRunning)`), so `mvn clean test` still works.
Start Redis to run all 40 tests.

## 18. Project structure

```text
src/main/java/com/example/ratelimiter/
├── DistributedRateLimiterApplication.java
├── controller/
│   ├── RateLimiterController.java        # /counter, /token-bucket, /redis, /test
│   └── RateLimitHeaders.java             # X-RateLimit-Limit / -Remaining / Retry-After
├── service/
│   ├── RateLimiterService.java           # common interface
│   ├── FixedWindowRateLimiter.java       # implementation 1
│   ├── TokenBucketRateLimiter.java       # implementation 2
│   ├── RedisTokenBucketRateLimiter.java  # implementation 3
│   ├── RateLimitPlanResolver.java        # per-user limits abstraction
│   └── DefaultRateLimitPlanResolver.java
├── model/
│   ├── RateLimitResponse.java
│   ├── BucketState.java                  # in-memory bucket + lazy refill
│   ├── RateLimitPlan.java                # capacity + refill rate
│   └── RateLimitAlgorithm.java
├── config/
│   ├── RateLimitProperties.java          # rate-limit.* properties
│   ├── RedisConfig.java                  # Lua script + Clock beans
│   └── RedisPingHealthIndicator.java     # /actuator/health redis check
└── exception/
    ├── RateLimitExceededException.java   # -> 429
    ├── InvalidUserIdException.java       # -> 400
    └── GlobalExceptionHandler.java
src/main/resources/
├── application.properties
└── scripts/token_bucket.lua
```

### Request flow (Redis endpoint)

```text
GET /api/rate-limit/redis  (X-User-Id: alice)
        |
RateLimiterController ── validate user id (400 if invalid)
        |
RedisTokenBucketRateLimiter
        |── RateLimitPlanResolver.resolve("alice") -> capacity=100, refill=10/s
        |── EVALSHA token_bucket.lua  KEYS=[rate-limit:token-bucket:alice]  ARGV=[100, 10, 3600]
        |        (atomic inside Redis: read -> refill -> cap -> check -> consume -> write -> EXPIRE)
        |<── {allowed, remaining, retryAfterMs}
        |── on Redis error: apply fail-open / fail-closed, log ERROR
        |
allowed?  yes -> 200 + X-RateLimit-* headers
          no  -> RateLimitExceededException -> GlobalExceptionHandler -> 429 + Retry-After
```

---

## 19. Interview questions and answers

**Why use Redis?**
All application instances need one shared view of each user's bucket. Redis is an in-memory, low-latency
store that every instance can reach. It runs commands one at a time and supports Lua scripts, which makes
the check-and-consume step atomic, and it has key TTLs for automatic cleanup.

**Why not a HashMap?**
A `HashMap` isn't thread-safe, and even a `ConcurrentHashMap` lives inside one JVM. It works on a single
server (implementations 1 and 2) but each instance keeps a separate copy, and the state is lost on restart.

**Why does local memory fail in distributed systems?**
The load balancer spreads a user's requests across servers, and each server counts only what it sees.
With N servers the effective limit becomes N × limit, and it changes whenever servers scale up, scale down or restart.

**Why Token Bucket?**
It allows controlled bursts (up to capacity) while enforcing a steady average rate (refill rate). It needs
only two numbers per user and O(1) work per request (lazy refill, no timers), and it has no window-boundary spike.

**Fixed Window vs Token Bucket?**
Fixed window is the simplest (one counter per window) but allows up to 2× the limit around a window boundary
and resets abruptly. Token bucket refills smoothly, caps bursts at capacity and avoids the boundary problem,
at the cost of slightly more math. (Sliding window log/counter are other alternatives.)

**Why Lua?**
To make read → refill → check → consume → write a single atomic operation inside Redis. A separate
GET/SET sequence lets two servers read the same token count and both admit a request. Lua also saves round
trips (one call instead of several). `MULTI/EXEC` alone can't do this, because the decision depends on values read
inside the transaction; `WATCH` + retry would work but costs retries under contention.

**What happens if two requests arrive simultaneously?**
In memory: the per-user lock (`compute()` / `synchronized` bucket) runs them one after the other.
In Redis: scripts run one at a time, so the second script sees the first one's write. With 1 token left,
exactly one request is allowed and the other gets 429. The concurrency tests verify this.

**Why not MySQL?**
Rate limiting runs on *every* request. A disk-backed relational database adds milliseconds of latency, row-lock
contention on hot users and write load proportional to traffic. Rate-limit state is short-lived and can be
lost without harm, which suits an in-memory store with TTLs.

**What happens if Redis goes down?**
The configured policy applies: `fail-open=true` allows traffic (availability), `fail-open=false` rejects it
(strict enforcement). Every failure is logged and `/actuator/health` shows Redis DOWN. Mitigations: Redis
replication with Sentinel failover, short timeouts, and optionally a degraded local in-memory limiter as fallback.

**How would you scale Redis?**
Replicas and Sentinel for high availability, and **Redis Cluster** to shard by key. One user's bucket is a
single key, so the Lua script stays single-slot and works unchanged in a cluster (hash tags like
`rate-limit:{user123}` keep multi-key scripts on one slot). Each request is one O(1) script call, so a single
node already handles a very high request rate.

**How would you support different limits for different users?**
Through `RateLimitPlanResolver`. The limiters never hardcode limits; they ask the resolver for a
`RateLimitPlan(capacity, refillRate)`, and the Lua script takes these as arguments. Adding FREE (100, 10/s)
and PREMIUM (1000, 100/s) tiers means writing a resolver that looks up the user's subscription
(cached), with no change to the algorithm or the script.

**How would you rate-limit by IP?**
Use the client IP as the key instead of (or together with) the user id, e.g. `rate-limit:token-bucket:ip:203.0.113.7`.
Behind a proxy or load balancer, read `X-Forwarded-For` only from trusted proxies (Spring's
`server.forward-headers-strategy`). Beware of NAT and shared IPs: use generous IP limits, and stricter limits per authenticated user.
Typically this would be a servlet filter or interceptor in front of every endpoint.

**What happens if one application server crashes?**
Nothing is lost: the buckets live in Redis, not in the server. The load balancer sends traffic to the
remaining instances, which keep using the same buckets, so users get no extra quota. With in-memory
limiters, the crashed server's counters would be lost and reset on restart.

---

## 20. Interview demonstration script

1. **Fixed window**: "I started with a simple counter allowing 100 requests per minute."
   Run `FixedWindowRateLimiterTest.fixedWindowBoundaryProblem`: 100 requests at 10:00:59 plus 100 at 10:01:00 equals 200 in one second.
2. **Token bucket**: "I then moved to a Token Bucket. Each request consumes one token and tokens refill
   continuously at a fixed rate." Use the curl drain/refill example on `/token-bucket`.
3. **Multiple servers**: start 8080/8081/8082. "The in-memory bucket works on one server, but with
   multiple servers each server has its own bucket." Use the `erin` loop: about 300 requests are allowed.
4. **Redis**: "Redis provides shared state, so all application instances use the same bucket for a user."
   Use the `alice` loop on `/redis`: about 100 requests are allowed in total across all three servers. Show `redis-cli HGETALL`.
5. **Atomicity**: "Since multiple servers can access the same bucket concurrently, the check-and-consume
   operation must be atomic. I use a Redis Lua script so refill, validation and token consumption execute
   as one atomic operation." Show `token_bucket.lua` and `concurrentRequestsConsumeExactlyCapacityTokens`.
