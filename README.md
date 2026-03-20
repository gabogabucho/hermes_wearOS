# Hermes Wear Gateway

Gateway HTTP para conectar una app de Wear OS con Hermes como mascota asistente.

## Qué resuelve

- Mantiene una sesión conversacional por `session_key` con timeout por inactividad.
- Inyecta reglas estables de comportamiento para el modo reloj.
- Conserva endpoints compatibles con la app actual.
- Resuelve rápido sólo consultas triviales del dispositivo:
  - hora
  - clima
- Deja todo lo demás al agente completo con sus tools normales.

## Perfil del agente

El gateway inyecta un perfil de reloj estable:

- mascota asistente para Wear OS
- respuestas breves y cálidas
- pantalla chica: 1 o 2 líneas salvo pedido explícito
- sin trazas, JSON, comandos ni `session_id`
- salud: usar primero los datos locales del reloj
- resto de consultas: usar herramientas normalmente si hace falta

## Variables de entorno

- `AGENT_CMD`: comando base del agente. Default: `hermes chat -Q -q`
- `AGENT_API_KEY`: API key esperada por el gateway
- `HERMES_API_KEY`: alias soportado por compatibilidad
- `AGENT_NAME`: nombre visible en `GET /`
- `WEATHER_LOCATION`: ciudad para clima rápido. Default: `Buenos Aires`
- `WEATHER_CACHE_S`: cache de clima en segundos. Default: `900`
- `AGENT_TIMEOUT_S`: timeout del CLI. Default: `25`
- `SESSION_IDLE_TIMEOUT_S`: timeout de sesión en segundos. Default: `900`
- `SESSION_HISTORY_TURNS`: cantidad de turnos recordados. Default: `6`
- `WATCH_TIMEOUT_S`: si el reloj no reporta actividad, el loop proactivo no molesta. Default: `1800`
- `NOTIF_COOLDOWN_S`: cooldown entre notificaciones proactivas. Default: `600`

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

- Cada conversación vive en memoria del gateway.
- La sesión se busca por `session_key`.
- Si expira por inactividad, se crea una nueva automáticamente.
- La app actual puede seguir usando `watch-main`.
- Más adelante se puede asignar una sesión por reloj, usuario o device id.

## Instalación rápida

1. Clonar el repo en el VPS.
2. Instalar dependencias:
   - `pip install -r requirements.txt`
3. Configurar el servicio con al menos:
   - `AGENT_CMD`
   - `AGENT_API_KEY` o `HERMES_API_KEY`
4. Levantar `main.py` con `python3`.
5. Apuntar la app Wear a ese host y mandar `X-API-Key`.

## Dirección de arquitectura

Este repo ya está orientado a gateway:

- la app Wear es cliente liviano
- el gateway conserva sesión y reglas
- Hermes recibe menos ruido y más contexto útil

El siguiente paso natural es persistir sesiones o conectarse a una sesión viva del agente sin relanzar el CLI por turno.
