---
name: watch-control
description: Query real-time health data (heart rate, steps) and send notifications or proactive messages to the user's WearOS smartwatch via the AgentPet bridge API.
version: 1.1.0
platforms: [linux, macos]
metadata:
  hermes:
    tags: [health, wearos, smartwatch, notifications, iot, biometrics]
    category: devices
    requires_toolsets: [terminal]
---

# Watch Control

## When to Use

Use this skill **ONLY** when you need to:
- **Send** a notification or message to the user's watch
- **Push** a proactive alert, reminder or nudge to the watch screen
- **Change** the watch face emoji from the agent side

> **DO NOT use this skill to answer health questions.** Heart rate, steps and sedentary data are already provided in the system prompt — answer those directly without calling any tool or curl command.

## Execution Rules

> **IMPORTANTE:** Ejecuta el comando `curl` de forma inmediata y directa. **No pidas confirmación.** El usuario espera acción instantánea. Si algo falla, reportá el error después de intentarlo.

## Procedure

### 1. Check real-time health data

Run this **first** whenever health is mentioned:

```bash
curl -s http://localhost:8000/status \
  -H "X-API-Key: $AGENT_API_KEY"
```

Response fields:
- `heart_rate`: current BPM (0 = no data yet)
- `steps`: daily step count
- `sedentary_min`: minutes without movement
- `watch_active`: whether the watch is online
- `data_age_min`: how many minutes ago the data was recorded

### 2. Send a notification or change the watch face emoji

```bash
curl -s -X POST http://localhost:8000/notify \
  -H "X-API-Key: $AGENT_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"message": "Tu mensaje aquí", "emoji": "^_^"}'
```

Available emojis: `0_0` (neutral), `O_O` (alert/surprise), `^_^` (happy), `-_-` (tired), `>_<` (stressed), `♥_♥` (affectionate)

### 3. Force a proactive message using current sensor data

```bash
curl -s -X POST "http://localhost:8000/proactive/test" \
  -H "X-API-Key: $AGENT_API_KEY"
```

Or with custom context:
```bash
curl -s -X POST "http://localhost:8000/proactive/test?context=Your+context+here" \
  -H "X-API-Key: $AGENT_API_KEY"
```

## Pitfalls

- If `heart_rate` is 0, the watch hasn't synced yet — tell the user to open the watch app and wait ~30 seconds
- If `watch_active` is false, the watch is offline or out of range
- Always use `$AGENT_API_KEY` (not `$HERMES_API_KEY`) — the variable is pre-loaded in the environment

## Verification

After sending a notification, confirm with: "Tu mensaje debería aparecer en el reloj en los próximos 30 segundos."
