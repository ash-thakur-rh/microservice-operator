package io.example.operator.customresource;

import java.util.List;
import java.util.Map;

/**
 * Defines everything needed to run and expose a microservice.
 * Users set this in their MicroService CR manifest.
 */
public class MicroServiceSpec {

  /** Container image, e.g. "quay.io/myorg/my-app:1.2.3" */
  private String image;

  /** Number of desired replicas (overridden by HPA when autoscaling is enabled). */
  private int replicas = 1;

  /** Port the container listens on. */
  private int containerPort = 8080;

  /** Environment-specific config key/value pairs mounted as a ConfigMap. */
  private Map<String, String> config;

  /**
   * Optional autoscaling settings. When present, an HPA is created and the Deployment
   * replicas field acts as the HPA minReplicas.
   */
  private AutoscalingSpec autoscaling;

  /**
   * When true, an Ingress (or Route on OpenShift) is created to expose the service externally.
   * The operator auto-detects the cluster type.
   */
  private boolean exposed = false;

  /** Optional ingress/route hostname. Auto-generated from resource name when omitted. */
  private String hostname;

  /** Resource requests and limits for the container. */
  private ResourceRequirementsSpec resources;

  /** Extra labels to merge onto all owned resources. */
  private Map<String, String> extraLabels;

  /** Optional list of secrets to mount as environment variables (secretRef). */
  private List<String> envFromSecrets;

  // --- Getters / Setters ---

  public String getImage() { return image; }
  public void setImage(String image) { this.image = image; }

  public int getReplicas() { return replicas; }
  public void setReplicas(int replicas) { this.replicas = replicas; }

  public int getContainerPort() { return containerPort; }
  public void setContainerPort(int containerPort) { this.containerPort = containerPort; }

  public Map<String, String> getConfig() { return config; }
  public void setConfig(Map<String, String> config) { this.config = config; }

  public AutoscalingSpec getAutoscaling() { return autoscaling; }
  public void setAutoscaling(AutoscalingSpec autoscaling) { this.autoscaling = autoscaling; }

  public boolean isExposed() { return exposed; }
  public void setExposed(boolean exposed) { this.exposed = exposed; }

  public String getHostname() { return hostname; }
  public void setHostname(String hostname) { this.hostname = hostname; }

  public ResourceRequirementsSpec getResources() { return resources; }
  public void setResources(ResourceRequirementsSpec resources) { this.resources = resources; }

  public Map<String, String> getExtraLabels() { return extraLabels; }
  public void setExtraLabels(Map<String, String> extraLabels) { this.extraLabels = extraLabels; }

  public List<String> getEnvFromSecrets() { return envFromSecrets; }
  public void setEnvFromSecrets(List<String> envFromSecrets) { this.envFromSecrets = envFromSecrets; }
}