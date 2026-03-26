package io.example.operator.customresource;

/**
 * Describes a PostgreSQL database that the operator will provision alongside the microservice.
 *
 * When this block is present in the CR the operator creates:
 *   - A Secret with auto-generated credentials (never touches user-managed secrets)
 *   - A headless Service for StatefulSet DNS + a ClusterIP Service for the app
 *   - A StatefulSet running PostgreSQL with a PersistentVolumeClaim
 *
 * The generated Secret is automatically injected into the microservice Deployment via envFrom,
 * so the app picks up SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, and
 * SPRING_DATASOURCE_PASSWORD without any manual configuration.
 */
public class DatabaseSpec {

  /** PostgreSQL container image. Defaults to the Red Hat UBI image, which supports
   *  arbitrary UIDs and works on both OpenShift and vanilla Kubernetes.
   *  For vanilla Kubernetes you may override with docker.io/postgres:15-alpine,
   *  but note that image uses POSTGRES_* env vars instead of POSTGRESQL_*. */
  private String image = "registry.access.redhat.com/rhel9/postgresql-15:9.7";

  /** Name of the database to create inside PostgreSQL. */
  private String databaseName = "appdb";

  /** PostgreSQL username. The password is always auto-generated. */
  private String username = "appuser";

  /**
   * Size of the PersistentVolumeClaim for PostgreSQL data.
   * Uses Kubernetes quantity notation, e.g. "1Gi", "10Gi".
   */
  private String storageSize = "1Gi";

  /**
   * StorageClass for the PVC. When null the cluster default StorageClass is used.
   * Set to e.g. "gp3" on AWS or "standard" on GKE.
   */
  private String storageClass;

  // --- Getters / Setters ---

  public String getImage() { return image; }
  public void setImage(String image) { this.image = image; }

  public String getDatabaseName() { return databaseName; }
  public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }

  public String getStorageSize() { return storageSize; }
  public void setStorageSize(String storageSize) { this.storageSize = storageSize; }

  public String getStorageClass() { return storageClass; }
  public void setStorageClass(String storageClass) { this.storageClass = storageClass; }
}