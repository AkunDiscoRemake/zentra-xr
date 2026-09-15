#!/bin/bash
# PINA VR - Run dev server
cd "$(dirname "$0")/web"
echo "=== PINA VR OS ==="
echo "Mixed Reality default, Cardboard, 100% 3D spatial"
echo ""
if command -v python3 &> /dev/null; then
  echo "Iniciando servidor Python na porta 8000..."
  echo "Abra no celular Android Chrome: http://SEU_IP:8000"
  echo "Para Cardboard, use HTTPS ou chrome://flags #unsafely-treat-insecure-origin-as-secure"
  python3 -m http.server 8000 --bind 0.0.0.0
else
  echo "Python não encontrado, tentando npx serve..."
  npx serve . -l 8000 -s
fi
