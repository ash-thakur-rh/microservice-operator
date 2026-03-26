package io.example.operator.customresource;

public class ResourceRequirementsSpec {

  private String requestsCpu = "100m";
  private String requestsMemory = "128Mi";
  private String limitsCpu = "500m";
  private String limitsMemory = "512Mi";

  public String getRequestsCpu() { return requestsCpu; }
  public void setRequestsCpu(String requestsCpu) { this.requestsCpu = requestsCpu; }

  public String getRequestsMemory() { return requestsMemory; }
  public void setRequestsMemory(String requestsMemory) { this.requestsMemory = requestsMemory; }

  public String getLimitsCpu() { return limitsCpu; }
  public void setLimitsCpu(String limitsCpu) { this.limitsCpu = limitsCpu; }

  public String getLimitsMemory() { return limitsMemory; }
  public void setLimitsMemory(String limitsMemory) { this.limitsMemory = limitsMemory; }
}