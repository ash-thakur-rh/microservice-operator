package io.example.operator.customresource;

public class AutoscalingSpec {

  /** Minimum number of replicas the HPA will scale down to. */
  private int minReplicas = 1;

  /** Maximum number of replicas the HPA will scale up to. */
  private int maxReplicas = 5;

  /** Target average CPU utilisation percentage (0–100) that triggers scaling. */
  private int targetCPUUtilizationPercentage = 70;

  public int getMinReplicas() { return minReplicas; }
  public void setMinReplicas(int minReplicas) { this.minReplicas = minReplicas; }

  public int getMaxReplicas() { return maxReplicas; }
  public void setMaxReplicas(int maxReplicas) { this.maxReplicas = maxReplicas; }

  public int getTargetCPUUtilizationPercentage() { return targetCPUUtilizationPercentage; }
  public void setTargetCPUUtilizationPercentage(int targetCPUUtilizationPercentage) {
    this.targetCPUUtilizationPercentage = targetCPUUtilizationPercentage;
  }
}