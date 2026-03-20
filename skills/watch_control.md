# Skill: Watch Control
# Description: Controls the virtual pet and notifications on the Google Pixel Watch via the local bridge API.

## Instructions
When you need to alert the user (e.g. from your internal reminders/cron, or if you detect a health anomaly like high heart rate or a notification event), you must send the alert directly to the user's watch by making an API call to the local bridge.

### To send a notification or change the emoji on the watch:
Use a tool to execute the following `curl` command. Replace the `message` and `emoji` fields appropriately:

```bash
curl -X POST http://localhost:8000/notify \
  -H "X-API-Key: hermes_secreto_2007" \
  -H "Content-Type: application/json" \
  -d '{"message": "¡Tu ritmo cardíaco está muy alto! ¿Estás bien?", "emoji": "O_O"}'
```

*(Note: Use the exact API Key configured in your system environment if different).*

### Available Emojis for the payload:
- `0_0`: Neutral/Idle.
- `O_O`: Surprised, worried (use for health alerts).
- `^_^`: Happy, task completed.
- `-_-`: Tired, sleepy, or low activity.
- `>_<`: Stressed or busy.
- `♥_♥`: Affectionate.

## Automatic Triggers & Proactive Behavior
- **Reminders (Cron):** If your internal cron system triggers a reminder, you must push it to the watch using the `/notify` curl command above, so the user receives it on their wrist immediately.
- **Health Anomalies:** If you receive external context that the user's heart rate exceeds 110 BPM and they aren't moving, ALWAYS push a notification asking if they are okay.
- **Location Events:** If you receive an update that the user reached a Google Maps destination but their steps remain unchanged, send a notification saying: "Llegaste a destino, recuerda moverte".
