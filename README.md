# ModNet

ModNet is a lightweight networking companion for Minecraft 1.21.8 that keeps the gameplay-critical TCP
channel intact while opening an optional UDP side channel for telemetry, cosmetics, and adaptive hints.

| Module | Role |
| --- | --- |
| `common` | Shared protocol, telemetry/cosmetic serializers, and UDP client/server primitives. |
| `fabric` | Fabric client/server adapters that reuse the shared protocol. |
| `forge` | Forge client/server adapters that speak the same plugin channel. |
| `paper` | Paper plugin that authenticates tokens and exposes `/modnet stats`. |

## Protocol highlights

- Header: `magic (MODN) + version (1) + type (byte) + length (short) + token (UUID) + payload`.
- Payloads: `TELEMETRY`, `COSMETIC`, and `HINT` (plus ping/pong). Each payload type is extensible,
  so future additions (voice, predictive compression hints, etc.) plug in cleanly.
- Handshake: all implementations speak the `modnet:main` plugin channel. Clients send `ClientHello`
  (random UUID); servers reply with `ServerHello` (UDP port + token). UDP packets must echo the token.
- Security: tokens expire on disconnect, UDP listeners validate tokens and rate-limit implicitly by
  checking caches before responding.
- Fallback: lack of UDP pongs or servers returning port `0` causes clients to keep using TCP.

## Building & testing

1. Install Java 17 and run `./gradlew` from the project root.
2. Build whichever module you need:

```bash
./gradlew :common:build   # includes unit tests for Protocol serialization
./gradlew :fabric:build
./gradlew :forge:build
./gradlew :paper:build
```

3. Run the `common` unit tests separately: `./gradlew :common:test`.

## Configuration & deployment

- **Fabric/Forge**: `config/modnet.properties` (generated from `modnet-default.properties` in resources).
  - `udp.enabled` toggles the UDP listener.
  - `udp.port` controls the UDP socket that clients connect to.
- **Paper**: `plugins/ModNet/config.yml` (values `udp-enabled` and `udp-port`).

Clients automatically detect UDP availability at login; if UDP pings fail after 5 seconds or the
server reports `udp-port: 0`, the mod quietly keeps using TCP.

## Runtime behavior

- Clients emit telemetry every second and cosmetic updates every few ticks via the shared `UdpClient`.
- Servers track telemetry per token and send hints (e.g., throttle cosmetics) whenever bandwidth drops
  below a threshold or packet loss spikes.
- All servers register `/modnet stats`, which reports the number of UDP-using players and their average loss.
- The Paper plugin keeps `token -> player` maps and exposes the UDP listener for vanilla/Ktor proxies.
- The shared `UdpServer` class can be re-used in proxies (Velocity/Bungee) by wiring its token cache to
  plugin messages.

## Testing recommendations

1. Launch a Paper server with `plugins/ModNet` and start a Fabric/Forge client with the ModNet mod.
2. Use Wireshark (filter `udp.port == 25566`) to confirm telemetry (`modnet:main` handshake + UDP payloads).
3. Trigger `/modnet stats` on the server to ensure telemetry is aggregated; the message should display
   how many clients are on UDP and their average packet loss.

## Next steps

1. Extend `Protocol.PacketType` for richer hints (bandwidth budgets, predictive compression metadata, etc.).
2. Connect the server hint listener to your TPS/congestion profiler and send stronger hints for proxies.
3. Instrument `/modnet stats` data for Grafana/Prometheus exports if you care about internet resilience.
