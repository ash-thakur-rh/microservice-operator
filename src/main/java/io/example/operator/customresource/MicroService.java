package io.example.operator.customresource;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * The MicroService Custom Resource.
 *
 * Example manifest:
 *
 * <pre>
 * apiVersion: example.io/v1
 * kind: MicroService
 * metadata:
 *   name: podinfo-demo
 *   namespace: default
 * spec:
 *   image: ghcr.io/stefanprodan/podinfo:latest
 *   replicas: 2
 *   containerPort: 9898
 *   config:
 *     PODINFO_UI_COLOR: "#336699"
 *     PODINFO_UI_MESSAGE: "Hello from MicroService Operator"
 *   autoscaling:
 *     minReplicas: 2
 *     maxReplicas: 10
 *     targetCPUUtilizationPercentage: 60
 *   exposed: true
 *   hostname: podinfo.apps.mycluster.example.com
 *   resources:
 *     requestsCpu: "100m"
 *     requestsMemory: "64Mi"
 *     limitsCpu: "500m"
 *     limitsMemory: "128Mi"
 *   envFromSecrets:
 *     - podinfo-token
 * </pre>
 */
@Group("example.io")
@Version("v1")
@ShortNames("ms")
public class MicroService extends CustomResource<MicroServiceSpec, MicroServiceStatus>
    implements Namespaced {
}