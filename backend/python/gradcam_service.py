#!/usr/bin/env python3
from flask import Flask, request, jsonify
from PIL import Image, ImageDraw
import base64
import io

app = Flask(__name__)

@app.route('/gradcam', methods=['POST'])
def gradcam():
    # Accept either an uploaded file or JSON with base64 image
    if 'image' in request.files:
        f = request.files['image']
        img = Image.open(f.stream).convert('RGBA')
    else:
        data = request.get_json(silent=True) or {}
        b64 = data.get('imageBase64')
        if not b64:
            return jsonify({'error': 'no image provided'}), 400
        img = Image.open(io.BytesIO(base64.b64decode(b64))).convert('RGBA')

    # Generate a simple gradient heatmap overlay the size of the source image
    w, h = img.size
    heat = Image.new('RGBA', (w, h))
    draw = ImageDraw.Draw(heat)
    for x in range(w):
        ratio = x / max(w-1, 1)
        r = int(255 * ratio)
        b = 255 - r
        draw.line([(x,0),(x,h)], fill=(r,0,b,180))
    # Composite small circular hotspot in the center
    draw.ellipse([(w*0.4,h*0.4),(w*0.6,h*0.6)], fill=(255,255,255,120))

    # Return base64-encoded PNG
    out = io.BytesIO()
    heat.save(out, format='PNG')
    out_b = out.getvalue()
    b64 = base64.b64encode(out_b).decode('ascii')
    return jsonify({'heatmapBase64': b64})

if __name__ == '__main__':
    app.run(host='127.0.0.1', port=5001)
