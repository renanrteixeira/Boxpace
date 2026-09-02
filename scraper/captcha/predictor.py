import io
from pathlib import Path

import torch
import torchvision.transforms as T
from PIL import Image

from captcha.model import CaptchaModel, decode_greedy, IMG_H, IMG_W

MODEL_PATH = Path(__file__).parent / "captcha_model.pt"

_model = None

_transform = T.Compose([
    T.Grayscale(),
    T.Resize((IMG_H, IMG_W)),
    T.ToTensor(),
    T.Normalize([0.5], [0.5]),
])


def predict(image_bytes: bytes) -> str:
    global _model
    if _model is None:
        if not MODEL_PATH.exists():
            raise FileNotFoundError(f"Modelo não encontrado: {MODEL_PATH}")
        m = CaptchaModel()
        m.load_state_dict(torch.load(MODEL_PATH, map_location="cpu", weights_only=True))
        m.eval()
        _model = m
    img = _transform(Image.open(io.BytesIO(image_bytes)).convert("RGB")).unsqueeze(0)
    with torch.no_grad():
        output = torch.log_softmax(_model(img), dim=2)
    return decode_greedy(output)[0]
