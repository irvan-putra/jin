# jin web companion - NEO App service image.
# Build context: this server/ directory (set "root directory" to server/
# in the NEO App Dockerfile settings).
FROM python:3.12-slim

WORKDIR /srv/jin

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app ./app
COPY tests ./tests

# The no-network unit tests gate every image build.
RUN cd /srv/jin && python -m unittest discover -s tests -v

ENV PORT=8080 \
    JIN_WORK_DIR=/work

RUN useradd -r -u 10001 -m jin && mkdir -p /work && chown jin:jin /work
USER jin

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD python -c "import os,urllib.request;urllib.request.urlopen('http://127.0.0.1:'+os.environ.get('PORT','8080')+'/healthz',timeout=4)"

ENTRYPOINT ["python", "app/start.py"]
