# ZENTRA XR - Hand Tracking Model

## MediaPipe Hand Landmarker

Este diretório deve conter o modelo `hand_landmarker.task` do MediaPipe.

### Como obter:

1. Acesse: https://developers.google.com/mediapipe/solutions/vision/hand_landmarker
2. Baixe o modelo `hand_landmarker.task` (ou `hand_landmarker_lite.task` para dispositivos low-end)
3. Coloque o arquivo aqui: `app/src/main/assets/hand_landmarker.task`

### Modelos recomendados para Beta 1:

- **hand_landmarker.task** (full) - ~15MB, melhor precisão
- **hand_landmarker_lite.task** - ~4MB, melhor performance, recomendado para Beta 1
- **hand_landmarker_heavy.task** - maior precisão, mas mais pesado

### Para Beta 1, use LITE para priorizar:

- LATÊNCIA BAIXA
- FPS ESTÁVEL
- BAIXO CONSUMO
- MENOS AQUECIMENTO

### Download direto (verifique URL atual no site oficial):

```bash
# Exemplo - verifique link atualizado na documentação MediaPipe
wget https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task -O app/src/main/assets/hand_landmarker.task
```

### Fallback:

Se o modelo não estiver presente, o app tentará inicializar sem asset e mostrará mensagem de erro clara ao usuário, em vez de crashar (conforme requisito de compatibilidade).

### Privacidade:

O modelo roda 100% localmente. Nenhuma imagem é enviada para servidores.
