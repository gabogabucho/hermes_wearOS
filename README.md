# Hermes Wear Gateway

Gateway HTTP para conectar una app de Wear OS con Hermes como mascota asistente.

## Qué resuelve

- Puede hablar con Hermes por el `api_server` oficial del gateway, evitando el ruido del CLI.
- Mantiene un mapeo entre `session_key` del reloj y `session_id` nativo de Hermes.
- Deja la memoria, `SOUL`, skills y session search en Hermes Agent.
- Conserva endpoints compatibles con la app actual.
- Resuelve rápido sólo consultas triviales del dispositivo:
  - hora
  - clima
- Deja todo lo demás al Hermes real con sus tools normales.

## Rol del bridge

El bridge no reemplaza la identidad del agente:

- Hermes sigue siendo el mismo agente
- la personalidad debería vivir en `SOUL.md`, personalidad o memoria nativa de Hermes
- el bridge sólo aporta contexto del dispositivo
- salud: agrega datos locales del reloj a la consulta cuando aplica
- watch UI: envía una pista de plataforma para decirle a Hermes "seguís siendo vos, pero en reloj hablá más directo, breve y expresivo"

## Variables de entorno

- `HERMES_API_SERVER_URL`: URL base del API server oficial de Hermes. Ej: `http://127.0.0.1:8642`
- `HERMES_API_SERVER_KEY`: Bearer token del API server si está protegido
- `AGENT_CMD`: comando base del agente. Default: `hermes chat -Q -q`
- `AGENT_RESUME_CMD`: comando para reanudar una sesión existente. Default: `hermes chat --resume {session_id} -Q -q`
- `AGENT_API_KEY`: API key esperada por el gateway
- `HERMES_API_KEY`: alias soportado por compatibilidad
- `AGENT_NAME`: nombre visible en `GET /`
- `WEATHER_LOCATION`: ciudad para clima rápido. Default: `Buenos Aires`
- `WEATHER_CACHE_S`: cache de clima en segundos. Default: `900`
- `AGENT_TIMEOUT_S`: timeout del CLI. Default: `25`
- `SESSION_MAP_PATH`: JSON local con el mapeo `session_key -> session_id`
- `WATCH_TIMEOUT_S`: si el reloj no reporta actividad, el loop proactivo no molesta. Default: `1800`
- `NOTIF_COOLDOWN_S`: cooldown entre notificaciones proactivas. Default: `600`
- `WATCH_PLATFORM_HINT`: instrucción opcional de plataforma para sesiones del reloj

## Endpoints

- `GET /`
  - health simple del gateway
- `GET /gateway/status`
  - estado de sesiones activas y configuración útil
- `POST /gateway/reset-session`
  - resetea una sesión por `session_key`
- `POST /health`
  - recibe `heart_rate` y `steps`
- `GET /status`
  - devuelve snapshot completo del estado local
- `GET /mood`
  - entrega emoji actual y una notificación pendiente
- `POST /chat`
  - body: `message`, opcional `session_key`
- `POST /voice-chat`
  - audio a texto + respuesta del agente
- `POST /notify`
  - inyecta una notificación externa al reloj
- `POST /proactive/test`
  - dispara una prueba del flujo proactivo

Todos salvo `GET /` requieren header `X-API-Key`.

## Sesiones

- Si `HERMES_API_SERVER_URL` está configurado, la sesión del watch vive en el `api_server` oficial de Hermes usando `conversation=session_key`.
- Hermes conserva la conversación real.
- El bridge sólo recuerda qué `session_id` de Hermes corresponde a cada `session_key`.
- Ese mapeo se persiste en un JSON local para sobrevivir reinicios del bridge.
- La app actual puede seguir usando `watch-main`.
- Más adelante se puede asignar una sesión por reloj, usuario o device id.

## Instalación rápida

1. Clonar el repo en el VPS.
2. Instalar dependencias:
   - `pip install -r requirements.txt`
3. Recomendado: levantar Hermes Gateway con `api_server`.
4. Configurar el servicio con al menos:
   - `HERMES_API_SERVER_URL`
   - opcional `HERMES_API_SERVER_KEY`
   - `AGENT_API_KEY` o `HERMES_API_KEY`
5. Si no usás `api_server`, el bridge cae al modo CLI legacy con:
   - `AGENT_CMD`
6. Levantar `main.py` con `python3`.
7. Apuntar la app Wear a ese host y mandar `X-API-Key`.

## Dirección de arquitectura

Este repo ya está orientado a gateway:

- la app Wear es cliente liviano
- el gateway traduce sensores, voz, wake y notificaciones
- Hermes conserva memoria, skills, `SOUL` y continuidad real

El backend preferido para el watch debería ser el `api_server` oficial de Hermes. El modo CLI queda sólo como compatibilidad/fallback.
