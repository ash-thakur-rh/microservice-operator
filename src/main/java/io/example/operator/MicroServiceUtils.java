package io.example.operator;

import java.util.HashMap;
import java.util.Map;

import io.example.operator.customresource.MicroService;

public final class MicroServiceUtils {

  private MicroServiceUtils() {}

  public static String configMapName(MicroService ms) {
    return ms.getMetadata().getName() + "-config";
  }

  /**
   * Returns a base set of labels applied to every resource owned by this CR.
   * Merges any extra labels defined in spec.extraLabels.
   */
  public static Map<String, String> appLabels(MicroService ms) {
    Map<String, String> labels = new HashMap<>();
    labels.put("app", ms.getMetadata().getName());
    labels.put("app.kubernetes.io/name", ms.getMetadata().getName());
    labels.put("app.kubernetes.io/managed-by", "microservice-operator");
    if (ms.getSpec().getExtraLabels() != null) {
      labels.putAll(ms.getSpec().getExtraLabels());
    }
    return labels;
  }

  /**
   * Returns the Ingress hostname: uses spec.hostname if set, otherwise derives
   * a sensible default from the resource name and namespace.
   */
  public static String resolveHostname(MicroService ms) {
    if (ms.getSpec().getHostname() != null && !ms.getSpec().getHostname().isBlank()) {
      return ms.getSpec().getHostname();
    }
    // Default: <name>-<namespace>.example.com — replace with your cluster domain
    return ms.getMetadata().getName() + "-" + ms.getMetadata().getNamespace() + ".example.com";
  }
}