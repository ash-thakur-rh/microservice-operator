package io.example.operator;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.example.operator.probes.LivenessHandler;
import io.example.operator.probes.StartupHandler;
import io.javaoperatorsdk.operator.Operator;

import com.sun.net.httpserver.HttpServer;

/**
 * Operator entry point.
 *
 * The operator picks up kubeconfig from the standard locations
 * (in-cluster service account when running inside a pod, or ~/.kube/config locally).
 */
public class MicroServiceOperator {

  private static final Logger log = LoggerFactory.getLogger(MicroServiceOperator.class);

  public static void main(String[] args) throws IOException {
    log.info("MicroService Operator starting");

    Operator operator = new Operator(cfg ->
        cfg.withStopOnInformerErrorDuringStartup(false));

    operator.register(new MicroServiceReconciler());
    operator.start();

    // Lightweight HTTP server for Kubernetes startup/liveness probes
    HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
    server.createContext("/startup", new StartupHandler(operator));
    server.createContext("/healthz", new LivenessHandler(operator));
    server.setExecutor(null); // use default executor
    server.start();

    log.info("MicroService Operator started — probe server listening on :8080");
  }
}