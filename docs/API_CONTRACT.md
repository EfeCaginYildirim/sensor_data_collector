# Vendor API contract (reconstructed)

The collector was originally written against a commercial condition-monitoring API during an
internship. That API, its credentials and the plant's data are no longer accessible, so this
contract was **reconstructed from the original client code** (the request builders and the
JSON parsing logic in `legacy/`). It is what `FakeMonitoringServer` implements and what the
client is tested against.

Statements marked **Assumption** could not be verified against the real service.

## Authentication

```
POST {base}/login
Content-Type: application/json

{"username": "...", "password": "..."}
```

Response `200`:

```json
{"accessToken": "eyJ..."}
```

All other endpoints require `Authorization: Bearer <accessToken>`.

- **Assumption:** an expired or revoked token yields `401`. The client re-authenticates once
  and repeats the request.
- **Assumption:** `429 Too Many Requests` may carry `Retry-After` in seconds.

## Plant hierarchy

```
GET {base}/plants/{plantId}
```

```json
{
  "status": true,
  "data": {
    "id": 1, "name": "...", "organizationName": "...", "odrEnabled": false,
    "areas": [{
      "id": 10, "name": "...", "status": 1,
      "machineGroups": [{
        "id": 20, "name": "...", "status": 1,
        "machines": [{
          "id": 30, "name": "...", "monitorCount": 2, "healthScore": 92,
          "monitors": [{
            "id": 101, "name": "...", "deviceIdentifier": "...", "orientationCode": "H",
            "subscriptionStart": "2024-01-01", "subscriptionEnd": "2027-01-01",
            "alarms": 0, "healthScore": 92, "status": 1, "trend": 0, "speed": 1485.0,
            "velocityX": 1.9, "velocityXOperationalThreshold": 2.8,
            "velocityXCautionThreshold": 4.5, "velocityXWarningThreshold": 7.1,
            "velocityY": ..., "velocityZ": ..., "temperature": ...
          }]
        }]
      }]
    }]
  }
}
```

- The legacy parser also accepted the plant object at the root (without `data`); the new
  client accepts both.
- Any field other than the ids may be missing or `null`.

## Basic features (daily aggregates)

```
GET {base}/trend-history?monitorId={id}&from={ts}&to={ts}&interval=1&intervalUnit=day
```

Current shape:

```json
{"status": true, "data": {"fingerprintData": [], "basicFeatures": [
  {"id": "bf-101-20100", "time": "2025-01-01T00:00:00.000Z", "monitorId": 101,
   "jsonAvg": "{\"velocityX\":1.9,\"velocityY\":1.6,\"velocityZ\":1.2,\"temperature\":41.2}",
   "jsonMin": "{...}", "jsonMax": "{...}"}
]}}
```

Older shape, still accepted: `{"status": true, "data": [ ...basic features... ]}`.

- `jsonAvg`, `jsonMin` and `jsonMax` are **strings that contain JSON**, not nested objects.
- **Assumption:** the keys inside them are `velocityX`, `velocityY`, `velocityZ` and
  `temperature`. The collector stores the strings verbatim, so this assumption only affects
  the synthetic data.

## Computed features

```
GET {base}/trend/computed-features?monitorId={id}&from={ts}&to={ts}
```

```json
{"status": true, "data": [
  {"timestamp": "2025-01-01T06:00:00.000Z", "KURTACCLX": 3.1, "...": 0.0, "TA": 38.5}
]}
```

The 29 feature names are listed in `ComputedFeature`. Missing features are omitted or `null`.

## Time parameters and timestamps

- `from` / `to` are sent as `yyyy-MM-dd'T'HH:mm:ss'Z'` (UTC), URL-encoded.
- **Assumption:** both bounds are **inclusive**. The client therefore treats every response
  as possibly overlapping its neighbours: it filters samples to its own half-open window
  `[from, to)` and removes duplicate timestamps.
- Timestamps in responses were seen as ISO-8601 with `Z`, and the legacy code also handled
  epoch seconds/milliseconds and zone-less local date-times. `Timestamps.parse` accepts all
  of these; zone-less values are interpreted as UTC (**Assumption**).
