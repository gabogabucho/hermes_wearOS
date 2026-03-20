#!/bin/bash
# Script para instalar el servicio de Hermes en Systemd de tu VPS
# Ejecútalo usando: sudo bash deploy_service.sh

echo "Iniciando instalación del servicio de Hermes Bridge..."

# Obtener la ruta completa de la carpeta actual (donde debe estar el proyecto)
CURRENT_DIR=$(pwd)
echo "Directorio actual (WorkingDirectory): $CURRENT_DIR"

# Archivo de destino
DEST_SERVICE="/etc/systemd/system/hermes-bridge.service"

# Copiamos nuestra plantilla y reemplazamos el directorio de trabajo dinámicamente
sed "s|/root/bridge|$CURRENT_DIR|g" hermes-bridge.service > $DEST_SERVICE

# Comprobamos e imprimimos PATH por si 'hermes' está en una ruta local
# Puedes ajustar el environment si la IA dice que no encuentra a hermes

# Recargamos la configuración de systemd
echo "Recargando demonio de Systemd..."
systemctl daemon-reload

# Habilitamos el servicio para que corra al reiniciar el VPS
echo "Habilitando servicio de arranque automático..."
systemctl enable hermes-bridge.service

# Iniciamos el servicio!
echo "Iniciando servicio hermes-bridge..."
systemctl start hermes-bridge.service

# Mostramos el estado
echo "=================================================="
echo "Servicio instalado correctamente!"
echo "Para ver si está corriendo, usa: systemctl status hermes-bridge.service"
echo "Para ver logs y posibles errores: journalctl -u hermes-bridge.service -f"
echo "=================================================="
