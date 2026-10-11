"""Entry point: uvicorn hopnet_server.main:app --host 0.0.0.0 --port 8000"""

import logging

from .app import create_app

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")

app = create_app()
