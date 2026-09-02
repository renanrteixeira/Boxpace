import torch.nn as nn

CHARSET = "0123456789abcdefghijklmnopqrstuvwxyz"
BLANK = len(CHARSET)
NUM_CLASSES = len(CHARSET) + 1

# Dimensões reais do CAPTCHA do site (215x80 natural)
IMG_H = 80
IMG_W = 215

# MaxPool H trace: 80 → 40 → 20 → 5 → 1


class CaptchaModel(nn.Module):
    def __init__(self):
        super().__init__()
        self.cnn = nn.Sequential(
            nn.Conv2d(1, 32, 3, padding=1), nn.BatchNorm2d(32), nn.ReLU(), nn.MaxPool2d(2, 2),
            nn.Conv2d(32, 64, 3, padding=1), nn.BatchNorm2d(64), nn.ReLU(), nn.MaxPool2d(2, 2),
            nn.Conv2d(64, 128, 3, padding=1), nn.BatchNorm2d(128), nn.ReLU(), nn.MaxPool2d((4, 1)),
            nn.Conv2d(128, 256, 3, padding=1), nn.BatchNorm2d(256), nn.ReLU(), nn.MaxPool2d((5, 1)),
        )
        self.lstm = nn.LSTM(256, 128, num_layers=2, bidirectional=True, batch_first=False, dropout=0.1)
        self.fc = nn.Linear(256, NUM_CLASSES)

    def forward(self, x):
        features = self.cnn(x)
        B, C, H, W = features.shape
        features = features.squeeze(2).permute(2, 0, 1)
        out, _ = self.lstm(features)
        return self.fc(out)


def decode_greedy(output):
    output = output.argmax(dim=2)
    results = []
    for b in range(output.shape[1]):
        seq = output[:, b].tolist()
        chars, prev = [], None
        for c in seq:
            if c != prev:
                if c != BLANK:
                    chars.append(CHARSET[c])
                prev = c
        results.append("".join(chars))
    return results
