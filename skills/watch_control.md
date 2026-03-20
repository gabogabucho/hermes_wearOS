# Skill: Watch Control
# Description: Controla la mascota virtual y notificaciones en el reloj vía la API del bridge local.

## Instrucciones
Cuando necesites alertar al usuario (por un recordatorio interno, anomalía de salud, o cualquier evento relevante),
envía la alerta directamente al reloj haciendo una llamada API al bridge.

### Para enviar una notificación o cambiar el emoji en el reloj:
```bash
curl -X POST http://localhost:8000/notify \
  -H "X-API-Key: $AGENT_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"message": "¡Tu ritmo cardíaco está muy alto! ¿Estás bien?", "emoji": "O_O"}'
```

*(La variable `AGENT_API_KEY` está cargada automáticamente por el sistema).*

### Emojis disponibles:
- `0_0`: Neutral / idle.
- `O_O`: Sorprendido / alerta (para alertas de salud).
- `^_^`: Feliz / tarea completada.
- `-_-`: Cansado / poca actividad.
- `>_<`: Estresado / ocupado.
- `♥_♥`: Afectuoso.

## Triggers automáticos y comportamiento proactivo
- **Recordatorios (cron):** Si tu sistema interno dispara un recordatorio, usa el curl de `/notify` para que el usuario lo reciba en la muñeca.
- **Anomalías de salud:** Si el ritmo cardíaco supera 110 BPM y el usuario no se está moviendo, SIEMPRE envía una notificación preguntando si está bien.
- **Eventos de ubicación:** Si el usuario llegó a un destino pero sus pasos no cambian, envía: "Llegaste a destino, recuerda moverte".
