package io.example.operator.dependentresource;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import io.example.operator.customresource.DatabaseSpec;
import io.example.operator.customresource.MicroService;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;

import static io.example.operator.MicroServiceUtils.*;

/**
 * Manages the database credentials Secret.
 *
 * <p>The operator generates a cryptographically random password on the first reconcile and
 * stores it in the Secret. On every subsequent reconcile the existing password is read back
 * and preserved — the operator never rotates credentials without being told to.
 *
 * <p>The Secret contains both the raw PostgreSQL variables (for the DB container) and the
 * Spring Boot Datasource variables (for the app container), so a single envFrom reference
 * in both the StatefulSet and the Deployment is enough to wire everything up.
 *
 * <pre>
 * Secret keys injected into the database container (Red Hat UBI postgresql image):
 *   POSTGRESQL_DATABASE        — database name
 *   POSTGRESQL_USER            — username
 *   POSTGRESQL_PASSWORD        — auto-generated password
 *
 * Secret keys injected into the application container:
 *   SPRING_DATASOURCE_URL      — jdbc:postgresql://&lt;db-svc&gt;:5432/&lt;dbname&gt;
 *   SPRING_DATASOURCE_USERNAME — same as POSTGRESQL_USER
 *   SPRING_DATASOURCE_PASSWORD — same as POSTGRESQL_PASSWORD
 * </pre>
 */
public class DatabaseSecretDependentResource
    extends CRUDKubernetesDependentResource<Secret, MicroService> {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  @Override
  protected Secret desired(MicroService ms, Context<MicroService> context) {
    DatabaseSpec db = ms.getSpec().getDatabase();

    // Preserve the password across reconcile loops — only generate once.
    String password = context.getSecondaryResource(Secret.class)
        .map(Secret::getData)
        .map(data -> data.get("POSTGRESQL_PASSWORD"))
        .map(b64 -> new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8))
        .orElseGet(DatabaseSecretDependentResource::generatePassword);

    String jdbcUrl = String.format(
        "jdbc:postgresql://%s:5432/%s", dbServiceName(ms), db.getDatabaseName());

    return new SecretBuilder()
        .withMetadata(new ObjectMetaBuilder()
            .withName(dbSecretName(ms))
            .withNamespace(ms.getMetadata().getNamespace())
            .withLabels(appLabels(ms))
            .build())
        // stringData is base64-encoded by the API server automatically
        .withStringData(Map.of(
            "POSTGRESQL_DATABASE",        db.getDatabaseName(),
            "POSTGRESQL_USER",            db.getUsername(),
            "POSTGRESQL_PASSWORD",        password,
            "SPRING_DATASOURCE_URL",      jdbcUrl,
            "SPRING_DATASOURCE_USERNAME", db.getUsername(),
            "SPRING_DATASOURCE_PASSWORD", password))
        .build();
  }

  private static String generatePassword() {
    byte[] bytes = new byte[24];
    SECURE_RANDOM.nextBytes(bytes);
    // URL-safe base64, no padding — safe to use in JDBC URLs and env vars
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}