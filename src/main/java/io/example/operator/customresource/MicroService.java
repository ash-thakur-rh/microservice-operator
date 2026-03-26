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
 *   name: payment-service
 *   namespace: production
 * spec:
 *   image: quay.io/acme/payment-service:2.1.0
 *   replicas: 2
 *   containerPort: 8080
 *   config:
 *     LOG_LEVEL: INFO
 *     DB_URL: jdbc:postgresql://postgres:5432/payments
 *   autoscaling:
 *     minReplicas: 2
 *     maxReplicas: 10
 *     targetCPUUtilizationPercentage: 60
 *   exposed: true
 *   hostname: payment.apps.mycluster.example.com
 *   resources:
 *     requestsCpu: "250m"
 *     requestsMemory: "256Mi"
 *     limitsCpu: "1000m"
 *     limitsMemory: "1Gi"
 *   envFromSecrets:
 *     - payment-db-credentials
 * </pre>
 */
@Group("example.io")
@Version("v1")
@ShortNames("ms")
public class MicroService extends CustomResource<MicroServiceSpec, MicroServiceStatus>
    implements Namespaced {
}