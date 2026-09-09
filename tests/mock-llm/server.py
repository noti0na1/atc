#!/usr/bin/env python3
"""A mock LLM server for run tests (no dependencies beyond the standard library).

Speaks the three wire protocols atc's adapters use, streaming over SSE:

    POST /v1/responses          OpenAI Responses API      ("api": "openai-responses")
    POST /v1/chat/completions   OpenAI chat completions   ("api": "openai")
    POST /v1/messages           Anthropic Messages API    ("api": "anthropic")
    GET  /health

Every conversation follows one script, whatever the protocol:

1. The first request (no tool result in the history) streams some *thinking*,
   then a `run_scala` tool call whose code is taken from the user's message
   (`run: <code>`, else `1 + 1`).
2. The request carrying the tool's result streams a text answer that quotes
   the result ("Result: ..."), and stops.

A non-streaming request gets the same content as one JSON document (this is
what `ChatModel.simple` sends). Usage numbers are fixed (100 in, 20 out) so a
transcript can assert on them.

    python3 tests/mock-llm/server.py --port 8089
"""
import argparse, json, sys, time, os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

THINKING = "The user wants me to run a snippet. I will call run_scala with it."
DEFAULT_CODE = "1 + 1"
USAGE_IN, USAGE_OUT = 100, 20


def log(message: str) -> None:
    print(f"[mock-llm] {message}", file=sys.stderr, flush=True)


# ---------------------------------------------------------------------------
# The script: what to answer, from what the request carries
# ---------------------------------------------------------------------------

def text_of(content) -> str:
    """The text of a message content (a string, or a list of typed parts)."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(part.get("text", "") for part in content if isinstance(part, dict))
    return ""


def code_from(prompt: str) -> str:
    prompt = prompt.strip()
    return prompt[len("run:"):].strip() if prompt.startswith("run:") else DEFAULT_CODE


def scenario(user_texts, tool_results):
    """("call", code) for the first turn, ("answer", text) once a tool result is in."""
    if tool_results:
        result = tool_results[-1].strip()
        return "answer", f"Result: {result}"
    prompt = user_texts[0] if user_texts else ""
    return "call", code_from(prompt)


# ---------------------------------------------------------------------------
# SSE and JSON helpers
# ---------------------------------------------------------------------------

def sse(event: str, data: dict) -> bytes:
    return f"event: {event}\ndata: {json.dumps(data)}\n\n".encode()


def data_only(data) -> bytes:
    payload = data if isinstance(data, str) else json.dumps(data)
    return f"data: {payload}\n\n".encode()


def chunks(text: str, size: int = 12):
    for i in range(0, len(text), size):
        yield text[i:i + size]


# ---------------------------------------------------------------------------
# OpenAI Responses API
# ---------------------------------------------------------------------------

def responses_inputs(body):
    user_texts, tool_results = [], []
    inputs = body.get("input", [])
    if isinstance(inputs, str):
        return [inputs], []
    for item in inputs:
        if not isinstance(item, dict):
            continue
        if item.get("type") == "function_call_output":
            tool_results.append(text_of(item.get("output", "")))
        elif item.get("role") == "user":
            user_texts.append(text_of(item.get("content", "")))
    return user_texts, tool_results


def responses_output_items(kind, payload):
    if kind == "call":
        arguments = json.dumps({"code": payload})
        return [
            {"id": "rs_1", "type": "reasoning", "summary": [{"type": "summary_text", "text": THINKING}]},
            {"id": "fc_1", "type": "function_call", "call_id": "call_1", "name": "run_scala",
             "arguments": arguments, "status": "completed"},
        ]
    return [{"id": "msg_1", "type": "message", "role": "assistant", "status": "completed",
             "content": [{"type": "output_text", "text": payload, "annotations": []}]}]


def responses_object(body, output, status):
    return {
        "id": "resp_1", "object": "response", "created_at": int(time.time()), "status": status,
        "model": body.get("model", "mock"), "output": output,
        "error": None, "incomplete_details": None, "instructions": None, "metadata": {},
        "parallel_tool_calls": True, "temperature": 1.0, "top_p": 1.0, "tool_choice": "auto", "tools": [],
        "usage": {"input_tokens": USAGE_IN, "input_tokens_details": {"cached_tokens": 0},
                  "output_tokens": USAGE_OUT, "output_tokens_details": {"reasoning_tokens": 5},
                  "total_tokens": USAGE_IN + USAGE_OUT},
    }


def responses_stream(body, kind, payload):
    seq = [0]

    def ev(event_type, **fields):
        seq[0] += 1
        fields.update({"type": event_type, "sequence_number": seq[0]})
        return sse(event_type, fields)

    items = responses_output_items(kind, payload)
    yield ev("response.created", response=responses_object(body, [], "in_progress"))
    yield ev("response.in_progress", response=responses_object(body, [], "in_progress"))
    for index, item in enumerate(items):
        started = dict(item)
        if item["type"] == "reasoning":
            started["summary"] = []
        elif item["type"] == "function_call":
            started = dict(item, arguments="", status="in_progress")
        else:
            started = dict(item, content=[], status="in_progress")
        yield ev("response.output_item.added", output_index=index, item=started)
        if item["type"] == "reasoning":
            yield ev("response.reasoning_summary_part.added", item_id=item["id"], output_index=index,
                     summary_index=0, part={"type": "summary_text", "text": ""})
            for piece in chunks(THINKING):
                yield ev("response.reasoning_summary_text.delta", item_id=item["id"], output_index=index,
                         summary_index=0, delta=piece)
            yield ev("response.reasoning_summary_text.done", item_id=item["id"], output_index=index,
                     summary_index=0, text=THINKING)
            yield ev("response.reasoning_summary_part.done", item_id=item["id"], output_index=index,
                     summary_index=0, part={"type": "summary_text", "text": THINKING})
        elif item["type"] == "function_call":
            for piece in chunks(item["arguments"]):
                yield ev("response.function_call_arguments.delta", item_id=item["id"], output_index=index, delta=piece)
            yield ev("response.function_call_arguments.done", item_id=item["id"], output_index=index,
                     name=item["name"], arguments=item["arguments"])
        else:
            text = item["content"][0]["text"]
            yield ev("response.content_part.added", item_id=item["id"], output_index=index, content_index=0,
                     part={"type": "output_text", "text": "", "annotations": []})
            for piece in chunks(text):
                yield ev("response.output_text.delta", item_id=item["id"], output_index=index, content_index=0,
                         delta=piece, logprobs=[])
            yield ev("response.output_text.done", item_id=item["id"], output_index=index, content_index=0,
                     text=text, logprobs=[])
            yield ev("response.content_part.done", item_id=item["id"], output_index=index, content_index=0,
                     part={"type": "output_text", "text": text, "annotations": []})
        yield ev("response.output_item.done", output_index=index, item=item)
    yield ev("response.completed", response=responses_object(body, items, "completed"))


# ---------------------------------------------------------------------------
# OpenAI chat completions
# ---------------------------------------------------------------------------

def chat_inputs(body):
    user_texts, tool_results = [], []
    for message in body.get("messages", []):
        role = message.get("role")
        if role == "user":
            user_texts.append(text_of(message.get("content", "")))
        elif role == "tool":
            tool_results.append(text_of(message.get("content", "")))
    return user_texts, tool_results


def chat_chunk(body, delta=None, finish_reason=None, usage=None):
    """One chunk. The usage chunk carries no choices, as OpenAI sends it: the SDK's
    accumulator builds the completion the moment it sees `usage`, from the choices
    finished by the chunks before."""
    chunk = {"id": "chatcmpl-1", "object": "chat.completion.chunk", "created": int(time.time()),
             "model": body.get("model", "mock"), "choices": []}
    if delta is not None:
        chunk["choices"] = [{"index": 0, "delta": delta, "finish_reason": finish_reason, "logprobs": None}]
    if usage is not None:
        chunk["usage"] = usage
    return data_only(chunk)


def chat_usage():
    return {"prompt_tokens": USAGE_IN, "completion_tokens": USAGE_OUT, "total_tokens": USAGE_IN + USAGE_OUT,
            "prompt_tokens_details": {"cached_tokens": 0}}


def chat_stream(body, kind, payload):
    yield chat_chunk(body, {"role": "assistant", "content": ""})
    if kind == "call":
        # Reasoning the way DeepSeek sends it: an extra `reasoning_content` field.
        for piece in chunks(THINKING):
            yield chat_chunk(body, {"reasoning_content": piece})
        arguments = json.dumps({"code": payload})
        yield chat_chunk(body, {"tool_calls": [{"index": 0, "id": "call_1", "type": "function",
                                                "function": {"name": "run_scala", "arguments": ""}}]})
        for piece in chunks(arguments):
            yield chat_chunk(body, {"tool_calls": [{"index": 0, "function": {"arguments": piece}}]})
        yield chat_chunk(body, {}, finish_reason="tool_calls")
    else:
        for piece in chunks(payload):
            yield chat_chunk(body, {"content": piece})
        yield chat_chunk(body, {}, finish_reason="stop")
    yield chat_chunk(body, usage=chat_usage())
    yield data_only("[DONE]")


def chat_object(body, kind, payload):
    if kind == "call":
        message = {"role": "assistant", "content": None, "reasoning_content": THINKING,
                   "tool_calls": [{"id": "call_1", "type": "function",
                                   "function": {"name": "run_scala", "arguments": json.dumps({"code": payload})}}]}
        finish = "tool_calls"
    else:
        message = {"role": "assistant", "content": payload}
        finish = "stop"
    return {"id": "chatcmpl-1", "object": "chat.completion", "created": int(time.time()),
            "model": body.get("model", "mock"),
            "choices": [{"index": 0, "message": message, "finish_reason": finish, "logprobs": None}],
            "usage": chat_usage()}


# ---------------------------------------------------------------------------
# Anthropic Messages API
# ---------------------------------------------------------------------------

def anthropic_inputs(body):
    user_texts, tool_results = [], []
    for message in body.get("messages", []):
        if message.get("role") != "user":
            continue
        content = message.get("content", "")
        if isinstance(content, str):
            user_texts.append(content)
            continue
        for part in content:
            if not isinstance(part, dict):
                continue
            if part.get("type") == "tool_result":
                tool_results.append(text_of(part.get("content", "")))
            elif part.get("type") == "text":
                user_texts.append(part.get("text", ""))
    return user_texts, tool_results


def anthropic_blocks(kind, payload):
    if kind == "call":
        return [{"type": "thinking", "thinking": THINKING, "signature": "mock-signature"},
                {"type": "tool_use", "id": "toolu_1", "name": "run_scala", "input": {"code": payload}}]
    return [{"type": "text", "text": payload}]


def anthropic_usage(output_tokens):
    return {"input_tokens": USAGE_IN, "output_tokens": output_tokens,
            "cache_creation_input_tokens": 0, "cache_read_input_tokens": 0,
            "server_tool_use": None, "service_tier": None}


def anthropic_message(body, blocks, stop_reason):
    return {"id": "msg_1", "type": "message", "role": "assistant", "model": body.get("model", "mock"),
            "content": blocks, "stop_reason": stop_reason, "stop_sequence": None, "container": None,
            "usage": anthropic_usage(USAGE_OUT)}


def anthropic_stream(body, kind, payload):
    blocks = anthropic_blocks(kind, payload)
    stop_reason = "tool_use" if kind == "call" else "end_turn"
    start = anthropic_message(body, [], None)
    start["usage"] = anthropic_usage(1)
    yield sse("message_start", {"type": "message_start", "message": start})
    for index, block in enumerate(blocks):
        if block["type"] == "thinking":
            yield sse("content_block_start", {"type": "content_block_start", "index": index,
                                              "content_block": {"type": "thinking", "thinking": "", "signature": ""}})
            for piece in chunks(block["thinking"]):
                yield sse("content_block_delta", {"type": "content_block_delta", "index": index,
                                                  "delta": {"type": "thinking_delta", "thinking": piece}})
            yield sse("content_block_delta", {"type": "content_block_delta", "index": index,
                                              "delta": {"type": "signature_delta", "signature": block["signature"]}})
        elif block["type"] == "tool_use":
            yield sse("content_block_start", {"type": "content_block_start", "index": index,
                                              "content_block": {"type": "tool_use", "id": block["id"],
                                                                "name": block["name"], "input": {}}})
            for piece in chunks(json.dumps(block["input"])):
                yield sse("content_block_delta", {"type": "content_block_delta", "index": index,
                                                  "delta": {"type": "input_json_delta", "partial_json": piece}})
        else:
            yield sse("content_block_start", {"type": "content_block_start", "index": index,
                                              "content_block": {"type": "text", "text": ""}})
            for piece in chunks(block["text"]):
                yield sse("content_block_delta", {"type": "content_block_delta", "index": index,
                                                  "delta": {"type": "text_delta", "text": piece}})
        yield sse("content_block_stop", {"type": "content_block_stop", "index": index})
    yield sse("message_delta", {"type": "message_delta",
                                "delta": {"stop_reason": stop_reason, "stop_sequence": None},
                                "usage": {"output_tokens": USAGE_OUT}})
    yield sse("message_stop", {"type": "message_stop"})


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

PROTOCOLS = {
    "/v1/responses": ("responses", responses_inputs,
                      lambda body, kind, payload: responses_object(body, responses_output_items(kind, payload), "completed"),
                      responses_stream),
    "/v1/chat/completions": ("chat", chat_inputs, chat_object, chat_stream),
    "/v1/messages": ("anthropic", anthropic_inputs,
                     lambda body, kind, payload: anthropic_message(body, anthropic_blocks(kind, payload),
                                                                   "tool_use" if kind == "call" else "end_turn"),
                     anthropic_stream),
}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    record_dir = None
    request_count = 0

    def log_message(self, fmt, *args):  # quiet the default access log
        pass

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, b"ok\n", "text/plain")
        else:
            self.reply(404, b"not found\n", "text/plain")

    def do_POST(self):
        path = self.path.split("?")[0]
        if path not in PROTOCOLS:
            self.reply(404, json.dumps({"error": {"message": f"unknown endpoint {path}"}}).encode(), "application/json")
            return
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length) if length else b""
        try:
            body = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            self.reply(400, json.dumps({"error": {"message": "invalid JSON"}}).encode(), "application/json")
            return
        name, inputs, whole, stream = PROTOCOLS[path]
        Handler.request_count += 1
        if Handler.record_dir:
            with open(os.path.join(Handler.record_dir, f"{Handler.request_count:03d}-{name}.json"), "w") as f:
                json.dump(body, f, indent=2)
        user_texts, tool_results = inputs(body)
        kind, payload = scenario(user_texts, tool_results)
        log(f"{name}: request {Handler.request_count}, {len(tool_results)} tool result(s) -> {kind} {payload[:60]!r}")
        if body.get("stream"):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            for piece in stream(body, kind, payload):
                self.wfile.write(piece)
                self.wfile.flush()
            self.close_connection = True
        else:
            self.reply(200, json.dumps(whole(body, kind, payload)).encode(), "application/json")

    def reply(self, status, payload, content_type):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        self.close_connection = True


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8089)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--record", metavar="DIR", help="dump every request body as JSON into DIR")
    args = parser.parse_args()
    if args.record:
        os.makedirs(args.record, exist_ok=True)
        Handler.record_dir = args.record
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    log(f"listening on http://{args.host}:{server.server_address[1]}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
