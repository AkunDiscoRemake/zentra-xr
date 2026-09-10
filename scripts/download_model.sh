#!/bin/bash
# ZENTRA XR - Download MediaPipe Hand Landmarker model
# Beta 1 uses LITE for performance

set -e

ASSETS_DIR="app/src/main/assets"
MODEL_FILE="$ASSETS_DIR/hand_landmarker.task"
LITE_URL="https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker_lite.task"
FULL_URL="https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"

mkdir -p "$ASSETS_DIR"

echo "📦 ZENTRA XR - Downloading hand tracking model..."

if [ -f "$MODEL_FILE" ]; then
  echo "✅ Model already exists: $MODEL_FILE ($(du -h $MODEL_FILE | cut -f1))"
  exit 0
fi

echo "⬇️  Trying LITE model (recommended for Beta 1 - low latency, low heat)..."
if curl -L --fail --retry 3 --retry-delay 5 "$LITE_URL" -o "$MODEL_FILE"; then
  echo "✅ LITE model downloaded: $(du -h $MODEL_FILE | cut -f1)"
  exit 0
fi

echo "⚠️  LITE failed, trying FULL model..."
if curl -L --fail --retry 3 "$FULL_URL" -o "$MODEL_FILE"; then
  echo "✅ FULL model downloaded: $(du -h $MODEL_FILE | cut -f1)"
  exit 0
fi

echo "❌ Failed to download model"
echo "ℹ️  App will still build and show clear error message instead of crashing (compatibility requirement)"
echo "ℹ️  Manual download: https://developers.google.com/mediapipe/solutions/vision/hand_landmarker"
exit 0
