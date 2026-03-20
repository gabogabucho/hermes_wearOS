#!/bin/bash
# Script para instalar el servicio de Hermes en Systemd de tu VPS
# IMPORTANTE: Ejecútalo SIN sudo inicialmente (el script pedirá tu contraseña luego si la necesita): 
# bash deploy_service.sh
echo "Iniciando instalación del servicio de Hermes Bridge..."

# Obtener la ruta completa de la carpeta actual (donde debe estar el proyecto)
CURRENT_DIR=$(pwd)
echo "Directorio actual (WorkingDirectory): $CURRENT_DIR"

# Archivo de destino temporal antes de copiar a systemd
DEST_TMP="/tmp/hermes-bridge.service"

# Detectar el Python que se está usando de verdad (por si tiene virtualenv)
REAL_PYTHON=$(which python3)
echo "Python detectado: $REAL_PYTHON"

# Copiamos nuestra plantilla y reemplazamos variables dinámicamente
sed "s|WorkingDirectory=/root/bridge|WorkingDirectory=$CURRENT_DIR|g" hermes-bridge.service > $DEST_TMP
sed -i "s|ExecStart=/usr/bin/python3|ExecStart=$REAL_PYTHON|g" $DEST_TMP

# Ahora sí lo movemos a systemd usando sudo
sudo cp $DEST_TMP /etc/systemd/system/hermes-bridge.service

# Comprobamos e imprimimos PATH por si 'hermes' está en una ruta local
# Puedes ajustar el environment si la IA dice que no encuentra a hermes

# Recargamos la configuración de systemd
echo "Recargando demonio de Systemd..."
sudo systemctl daemon-reload

# Habilitamos el servicio para que corra al reiniciar el VPS
echo "Habilitando servicio de arranque automático..."
sudo systemctl enable hermes-bridge.service

# Iniciamos el servicio!
echo "Iniciando servicio hermes-bridge..."
sudo systemctl start hermes-bridge.service

# Mostramos el estado
echo "=================================================="
echo "Servicio instalado correctamente!"
echo "Para ver si está corriendo, usa: systemctl status hermes-bridge.service"
echo "Para ver logs y posibles errores: journalctl -u hermes-bridge.service -f"
echo "=================================================="
