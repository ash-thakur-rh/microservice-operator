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
 *   name: petclinic-rest
 *   namespace: production
 * spec:
 *   image: docker.io/springcommunity/spring-petclinic:latest
 *   replicas: 2
 *   containerPort: 8080
 *   livenessPath: /actuator/health/liveness
 *   readinessPath: /actuator/health/readiness
 *   config:
 *     MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: "true"
 *     MANAGEMENT_HEALTH_LIVENESSSTATE_ENABLED: "true"
 *     MANAGEMENT_HEALTH_READINESSSTATE_ENABLED: "true"
 *     SPRING_PROFILES_ACTIVE: postgres
 *     LOGGING_LEVEL_ORG_SPRINGFRAMEWORK: WARN
 *   autoscaling:
 *     minReplicas: 2
 *     maxReplicas: 8
 *     targetCPUUtilizationPercentage: 65
 *   exposed: true
 *   hostname: petclinic-api.apps.mycluster.example.com
 *   resources:
 *     requestsCpu: "250m"
 *     requestsMemory: "512Mi"
 *     limitsCpu: "1"
 *     limitsMemory: "1Gi"
 *   envFromSecrets:
 *     - petclinic-db-credentials
 * </pre>
 */
@Group("example.io")
@Version("v1")
@ShortNames("ms")
public class MicroService extends CustomResource<MicroServiceSpec, MicroServiceStatus>
    implements Namespaced {
}