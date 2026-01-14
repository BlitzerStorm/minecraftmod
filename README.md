# ModNet (Minecraft 1.21.8)

ModNet is a cross-platform Minecraft **1.21.8** mod/plugin that opens an **optional UDP side-channel**
alongside the standard TCP plugin channel (`modnet:main`). Only non-critical traffic goes over UDP:
telemetry (client performance stats), cosmetics (particles/capes/nametags), and adaptive hints from
the server (e.g., “reduce cosmetic rate”). Gameplay-critical packets remain on TCP.

## How it works

1. Client sends `ClientHello` on `modnet:main`.
2. Server replies with `ServerHello` containing the UDP port and token.
3. Client starts UDP and sends telemetry/cosmetics; server sends hints back.
4. UDP payloads are length-prefixed, versioned, checksum-validated, and optionally compressed.
5. Hint packets are retried with acknowledgements (simple reliability layer).

## Modules

| Module | Role |
| --- | --- |
| `common` | Protocol, UDP client/server, retries, rate limiting, tests. |
| `fabric` | Fabric client + dedicated server adapters (Yarn mappings). |
| `forge` | Forge/NeoForge adapters using Mojang official mappings. |
| `paper` | Paper plugin with `/modnet stats` command. |

## Build (Java 17)

```bash
./gradlew :common:build
./gradlew :fabric:build
./gradlew :forge:build
./gradlew :paper:build
```

## Configure

### Fabric/Forge (`config/modnet.properties`)
```
udp.enabled=true
udp.port=25566
udp.portMin=25566
udp.portMax=25566
udp.maxPayloadBytes=8192
udp.compressionThresholdBytes=512
udp.rateLimitPerSecond=100
udp.rateLimitBurst=200
udp.sessionTimeoutSeconds=90
udp.hintRetryCount=3
udp.hintRetryIntervalMillis=750
udp.socketTimeoutMillis=1000
udp.fallbackTimeoutMillis=5000
cosmetic.rateLimitTicks=5
log.level=info
```

### Paper (`plugins/ModNet/config.yml`)
```yaml
udp-enabled: true
udp-port: 25566
udp-port-min: 25566
udp-port-max: 25566
udp-max-payload-bytes: 8192
udp-compression-threshold-bytes: 512
udp-rate-limit-per-second: 100
udp-rate-limit-burst: 200
udp-session-timeout-seconds: 90
udp-hint-retry-count: 3
udp-hint-retry-interval-millis: 750
udp-socket-timeout-millis: 1000
udp-fallback-timeout-millis: 5000
cosmetic-rate-limit-ticks: 5
log-level: info
```

## Runtime commands

`/modnet stats` — shows active UDP users and packet counts (received/sent/dropped/invalid).

## Troubleshooting

* **No UDP traffic**: ensure `udp.enabled` is true and UDP port is open.
* **Fallback to TCP**: clients log UDP fallback after no PONGs within `udp.fallbackTimeoutMillis`.
* **Drops increasing**: raise `udp.rateLimitPerSecond` or `udp.maxPayloadBytes` as needed.
