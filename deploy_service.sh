#!/bin/bash
# Script para instalar el servicio AgentPet Bridge en Systemd de tu VPS
# Ejecútalo SIN sudo inicialmente (el script pedirá contraseña cuando la necesite):
# bash deploy_service.sh

echo "Iniciando instalación del servicio AgentPet Bridge..."

CURRENT_DIR=$(pwd)
echo "Directorio actual (WorkingDirectory): $CURRENT_DIR"

DEST_TMP="/tmp/agentpet-bridge.service"
REAL_PYTHON=$(which python3)
echo "Python detectado: $REAL_PYTHON"

# Copiar plantilla y reemplazar variables dinámicas
sed "s|WorkingDirectory=/root/bridge|WorkingDirectory=$CURRENT_DIR|g" agentpet-bridge.service > $DEST_TMP
sed -i "s|ExecStart=/usr/bin/python3|ExecStart=$REAL_PYTHON|g" $DEST_TMP

# Inyectar PATH actual para que el agente sea encontrado por Systemd
sed -i "/\[Service\]/a Environment=\"PATH=$PATH\"" $DEST_TMP

# Inyectar API Key si está definida
if [ -n "$AGENT_API_KEY" ]; then
    echo "API KEY personalizada detectada. Añadiéndola a Systemd..."
    sed -i "/\[Service\]/a Environment=\"AGENT_API_KEY=$AGENT_API_KEY\"" $DEST_TMP
fi

# Inyectar comando de agente si está definido
if [ -n "$AGENT_CMD" ]; then
    echo "AGENT_CMD personalizado detectado: $AGENT_CMD"
    sed -i "/\[Service\]/a Environment=\"AGENT_CMD=$AGENT_CMD\"" $DEST_TMP
fi

# Instalar en systemd
sudo cp $DEST_TMP /etc/systemd/system/agentpet-bridge.service

echo "Recargando demonio de Systemd..."
sudo systemctl daemon-reload

echo "Habilitando servicio para arranque automático..."
sudo systemctl enable agentpet-bridge.service

echo "Iniciando servicio agentpet-bridge..."
sudo systemctl start agentpet-bridge.service

echo "=================================================="
echo "Servicio instalado correctamente!"
echo "Para ver si está corriendo: systemctl status agentpet-bridge.service"
echo "Para ver logs:              journalctl -u agentpet-bridge.service -f"
echo "=================================================="
