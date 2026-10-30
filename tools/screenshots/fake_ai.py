"""A stand-in OpenAI-compatible AI service for the README's AI screenshots: always the same
realistic estimate, no real key and no network (roadmap 2.0, Phase 5.3).

  python -X utf8 tools/screenshots/fake_ai.py [--port 8765]

The emulator reaches it through `adb reverse tcp:8765 tcp:8765` as http://127.0.0.1:8765.
"""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL = "demo-vision"

ESTIMATE = {
    "meal_name": "Grilled chicken salad",
    "items": [
        {"name": "Grilled chicken breast", "usda_query": "Chicken breast, grilled, meat only", "preparation": "grilled",
         "grams": 120, "grams_low": 100, "grams_high": 150, "confidence": 0.8, "is_hidden_ingredient": False,
         "nutrients": {"kcal": 198, "protein_g": 37.2, "carbs_g": 0, "fat_g": 4.3, "sodium_mg": 89}},
        {"name": "Mixed greens", "usda_query": "Lettuce, mixed greens, raw", "preparation": "raw",
         "grams": 80, "grams_low": 60, "grams_high": 110, "confidence": 0.85, "is_hidden_ingredient": False,
         "nutrients": {"kcal": 14, "protein_g": 1.1, "carbs_g": 2.6, "fat_g": 0.2, "fiber_g": 1.7}},
        {"name": "Avocado", "usda_query": "Avocado, raw", "preparation": "raw",
         "grams": 50, "grams_low": 35, "grams_high": 70, "confidence": 0.75, "is_hidden_ingredient": False,
         "nutrients": {"kcal": 80, "protein_g": 1.0, "carbs_g": 4.3, "fat_g": 7.3, "fiber_g": 3.4}},
        {"name": "Cherry tomatoes", "usda_query": "Tomatoes, cherry, raw", "preparation": "raw",
         "grams": 60, "grams_low": 40, "grams_high": 80, "confidence": 0.8, "is_hidden_ingredient": False,
         "nutrients": {"kcal": 11, "protein_g": 0.5, "carbs_g": 2.3, "fat_g": 0.1}},
        {"name": "Olive oil dressing", "usda_query": "Salad dressing, olive oil and vinegar", "preparation": "sauce",
         "grams": 15, "grams_low": 8, "grams_high": 25, "confidence": 0.5, "is_hidden_ingredient": True,
         "nutrients": {"kcal": 68, "protein_g": 0, "carbs_g": 0.4, "fat_g": 7.5}},
    ],
    "overall_confidence": 0.75,
    "assumptions": ["A typical restaurant portion of chicken", "About a tablespoon of olive oil dressing"],
    "clarifying_question": None,
}


class Handler(BaseHTTPRequestHandler):
    def _send(self, body):
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):  # noqa: N802 (http.server's naming)
        if self.path.rstrip("/").endswith("/models"):
            self._send({"object": "list", "data": [{"id": MODEL, "object": "model", "owned_by": "demo"}]})
        else:
            self.send_error(404)

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", 0))
        self.rfile.read(length)
        if not self.path.rstrip("/").endswith("/chat/completions"):
            self.send_error(404)
            return
        time.sleep(1.5)  # long enough to see the progress state
        self._send({
            "id": "demo", "object": "chat.completion", "created": int(time.time()), "model": MODEL,
            "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": json.dumps(ESTIMATE)}}],
            "usage": {"prompt_tokens": 1200, "completion_tokens": 400, "total_tokens": 1600},
        })

    def log_message(self, fmt, *args):
        print("fake_ai:", fmt % args)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8765)
    a = p.parse_args()
    ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()
