# Skill: Watch Control
# Description: Controla la mascota virtual, consulta salud y envía notificaciones al reloj vía la API del bridge local.

## Cuándo usar esta skill
- El usuario pregunta por su salud, pulsaciones, pasos, si está activo, etc.
- Quieres mandarle un mensaje, recordatorio o alerta al reloj.
- Detectas que el usuario puede necesitar un nudge (pausa, hidratación, movimiento, etc.).

---

## 1. Consultar estado de salud actual

Úsalo **siempre que el usuario pregunte por sus pulsaciones, pasos, o actividad física**.

```bash
curl -s http://localhost:8000/status \
  -H "X-API-Key: $AGENT_API_KEY"
```

**Respuesta:**
```json
{
  "heart_rate": 78,
  "steps": 3420,
  "emoji": "0_0",
  "watch_active": true,
  "data_age_min": 4.2,
  "sedentary_min": 12.0
}
```
- `data_age_min`: hace cuántos minutos se recibió el último dato del reloj.
- `sedentary_min`: minutos acumulados sin cambio en los pasos.
- Si `watch_active` es `false`, el reloj no ha reportado en los últimos 30 min.

---

## 2. Enviar notificación o cambiar emoji al reloj

```bash
curl -X POST http://localhost:8000/notify \
  -H "X-API-Key: $AGENT_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"message": "¡Hora de moverse un poco!", "emoji": "^_^"}'
```

### Emojis disponibles:
| Emoji  | Uso |
|--------|-----|
| `0_0`  | Neutral / idle |
| `O_O`  | Sorpresa / alerta de salud |
| `^_^`  | Feliz / tarea completada |
| `-_-`  | Cansado / poca actividad |
| `>_<`  | Estresado / ocupado |
| `♥_♥`  | Afectuoso |

---

## 3. Forzar un mensaje proactivo (test / acción directa)

```bash
# Sin contexto — usa los datos actuales del sensor
curl -X POST "http://localhost:8000/proactive/test" \
  -H "X-API-Key: $AGENT_API_KEY"

# Con contexto personalizado
curl -X POST "http://localhost:8000/proactive/test?context=El+usuario+acaba+de+terminar+una+reunión+larga.+Mándale+un+mensaje+de+pausa." \
  -H "X-API-Key: $AGENT_API_KEY"
```

---

## Comportamiento proactivo automático (loop interno del bridge)
El bridge ya monitorea autónomamente y notifica cuando:
- **HR > 110 BPM** por 2+ minutos consecutivos → pregunta si está bien (`O_O`)
- **45+ min sin moverse** → recordatorio de pausa activa

**Tú** debes notificar adicionalmente cuando:
- El usuario menciona que está cansado, estresado, o con dolor → chequea `/status` y reacciona
- Detectas contexto relevante (terminó una tarea, es hora de almorzar, etc.) → manda un mensaje con `/notify`
- El usuario pregunta "¿cómo estoy?" o similar → llama `/status` y responde con los datos reales
