# Imagem do scraper (Correios CAPTCHA + J&T) para hospedar o backend.
#
# Build:  docker build -t boxpace-scraper -f scraper/Dockerfile scraper/
# Run:    docker run --rm -p 8000:8000 boxpace-scraper
# Hosts que falam com a porta injetada ($PORT): Render, Fly, HF Spaces, Cloud Run.
FROM python:3.12-slim

WORKDIR /service

COPY requirements.txt .
# torch/torchvision vêm primeiro do index CPU (imagem ~+1,8 GB a menos que CUDA).
# O `pip install -r` depois acha o torch já satisfeito e mantém o wheel CPU.
RUN pip install --no-cache-dir \
    torch torchvision \
    --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000
CMD ["sh", "-c", "uvicorn app:app --host 0.0.0.0 --port ${PORT:-8000}"]