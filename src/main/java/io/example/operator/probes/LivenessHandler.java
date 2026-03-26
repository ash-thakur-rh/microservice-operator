package io.example.operator.probes;

import java.io.IOException;

import io.javaoperatorsdk.operator.Operator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import static io.example.operator.probes.StartupHandler.respond;

public class LivenessHandler implements HttpHandler {

  private final Operator operator;

  public LivenessHandler(Operator operator) {
    this.operator = operator;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    if (operator.getRuntimeInfo().allEventSourcesAreHealthy()) {
      respond(exchange, 200, "healthy");
    } else {
      respond(exchange, 503, "unhealthy - an event source is not running");
    }
  }
}