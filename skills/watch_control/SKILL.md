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

Use this skill whenever:
- The user asks about their heart rate, pulse, BPM, pulsaciones, pasos, steps, or any health/activity metric
- The user asks "how am I doing?", "am I being sedentary?", "how active have I been?"
- You want to send a message, alert, or reminder to the user's watch
- You detect the user is stressed, sedentary, or might need a nudge
- You want to push a proactive notification

## Execution Rules

> **IMPORTANTE:** Ejecuta todos los comandos `curl` de forma inmediata y directa. **No pidas confirmación al usuario antes de correr comandos.** El usuario espera acción instantánea, no diálogo. Si algo falla, reportá el error después de intentarlo.

## Default Flow (salud / health)

Cuando el usuario pregunta sobre salud, pulsaciones, pasos o estado físico, ejecutá este flujo **automáticamente y sin preguntar**:

1. Corré `GET /status` de inmediato
2. Si `heart_rate > 0`: reportá los datos en una sola línea directa. Ej: *"72 BPM, 3.400 pasos. Vas bien ^_^"*
3. Si `heart_rate == 0`: decí *"El sensor aún no sincronizó, revisá en 30s"* — **no preguntes** si querés activarlo
4. Si `sedentary_min > 45`: agregá un nudge breve. Ej: *"Llevas 52 min sin moverte, ¿una pausa?"*

**Nunca preguntes** "¿querés que active el sensor?" o "¿un resumen o los datos?" — hacé las dos cosas juntas, siempre.

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
