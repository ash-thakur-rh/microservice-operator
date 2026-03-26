package io.example.operator.probes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import io.javaoperatorsdk.operator.Operator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class StartupHandler implements HttpHandler {

  private final Operator operator;

  public StartupHandler(Operator operator) {
    this.operator = operator;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    if (operator.getRuntimeInfo().isStarted()) {
      respond(exchange, 200, "started");
    } else {
      respond(exchange, 503, "not started yet");
    }
  }

  static void respond(HttpExchange exchange, int code, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(code, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }
}