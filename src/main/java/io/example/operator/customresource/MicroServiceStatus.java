package io.example.operator.customresource;

/**
 * Reflects the observed state of the managed microservice back to the user.
 */
public class MicroServiceStatus {

  public enum Phase { PENDING, RUNNING, DEGRADED, ERROR }

  /** High-level lifecycle phase. */
  private Phase phase;

  /** Number of ready pods observed in the Deployment. */
  private int readyReplicas;

  /** URL at which the service is reachable externally (populated when exposed=true). */
  private String url;

  /** Name of the ConfigMap holding the app config. */
  private String configMapName;

  /** Human-readable message describing the current state or last error. */
  private String message;

  /** Operator-assigned generation at which this status was last observed. */
  private Long observedGeneration;

  // --- Getters / Setters ---

  public Phase getPhase() { return phase; }
  public void setPhase(Phase phase) { this.phase = phase; }

  public int getReadyReplicas() { return readyReplicas; }
  public void setReadyReplicas(int readyReplicas) { this.readyReplicas = readyReplicas; }

  public String getUrl() { return url; }
  public void setUrl(String url) { this.url = url; }

  public String getConfigMapName() { return configMapName; }
  public void setConfigMapName(String configMapName) { this.configMapName = configMapName; }

  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }

  public Long getObservedGeneration() { return observedGeneration; }
  public void setObservedGeneration(Long observedGeneration) {
    this.observedGeneration = observedGeneration;
  }

  @Override
  public String toString() {
    return "MicroServiceStatus{phase=" + phase + ", readyReplicas=" + readyReplicas
        + ", url='" + url + "', message='" + message + "'}";
  }
}