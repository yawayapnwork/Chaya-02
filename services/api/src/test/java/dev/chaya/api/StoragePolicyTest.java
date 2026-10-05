package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Object-storage access, tested on its own, below the API (docs/security.md, "Object storage"). Runs the real
 * infra/docker/minio/init.sh against a real MinIO and checks what each service account can and cannot do with the keys
 * the API lays out (TenantKeys). Whatever the API decides, these credentials are what a compromised worker would hold.
 */
class StoragePolicyTest extends AbstractIntegrationTest {

    static final String RAW = "policy-raw";
    static final String DER = "policy-derived";
    static final String[][] ACCOUNTS = {
        {"S3_ACCESS_KEY", "policy-api", "S3_SECRET_KEY", "policy-api-secret"},
        {"S3_WORKER_ACCESS_KEY", "policy-worker", "S3_WORKER_SECRET_KEY", "policy-worker-secret"},
        {"S3_RECON_ACCESS_KEY", "policy-recon", "S3_RECON_SECRET_KEY", "policy-recon-secret"},
        {"S3_BACKUP_ACCESS_KEY", "policy-backup", "S3_BACKUP_SECRET_KEY", "policy-backup-secret"},
    };
    private static boolean initialized;

    final String tenant = "org/" + UUID.randomUUID() + "/venue/" + UUID.randomUUID() + "/";
    final String rawKey = tenant + "capture/c/raw/m";
    final String workerKey = tenant + "scan/s/run/r/SPLAT_RECONSTRUCTION/attempt-1/scene.ply";
    final String piiKey = tenant + "scan/s/run/r/FFMPEG_PREPROCESS/attempt-1/pii/frame-000001.png";
    final String sealedKey = "sealed/" + tenant + "scan/s/run/r/SPLAT_RECONSTRUCTION/attempt-1/" + UUID.randomUUID() + "/scene.ply";
    final String sealedPiiKey = "sealed/" + tenant + "scan/s/run/r/FFMPEG_PREPROCESS/attempt-1/pii/" + UUID.randomUUID() + "/frame-000001.png";

    S3Client api;
    S3Client worker;
    S3Client recon;
    S3Client backup;

    @BeforeEach
    void runTheRealInitScript() throws Exception {
        synchronized (StoragePolicyTest.class) {
            if (!initialized) {
                MINIO.copyFileToContainer(MountableFile.forHostPath(Path.of("../../infra/docker/minio/init.sh")), "/tmp/init.sh");
                StringBuilder env = new StringBuilder("MINIO_URL=http://localhost:9000 MINIO_ROOT_USER=testaccess "
                    + "MINIO_ROOT_PASSWORD=testsecret123 S3_BUCKET_RAW=" + RAW + " S3_BUCKET_DERIVED=" + DER);
                for (String[] a : ACCOUNTS) {
                    env.append(' ').append(a[0]).append('=').append(a[1]).append(' ').append(a[2]).append('=').append(a[3]);
                }
                for (int run = 0; run < 2; run++) { // it runs on every `docker compose up`: it must be idempotent
                    ExecResult r = MINIO.execInContainer("sh", "-c", env + " sh /tmp/init.sh");
                    assertThat(r.getExitCode()).as(r.getStdout() + r.getStderr()).isZero();
                }
                initialized = true;
            }
        }
        api = client(ACCOUNTS[0]);
        worker = client(ACCOUNTS[1]);
        recon = client(ACCOUNTS[2]);
        backup = client(ACCOUNTS[3]);
        // what the system holds: raw media, a worker output, PII staging, and sealed copies (written by the API account)
        put(api, RAW, rawKey);
        for (String k : new String[] {workerKey, piiKey, sealedKey, sealedPiiKey}) {
            put(api, DER, k);
        }
    }

    private static S3Client client(String[] account) {
        return S3Client.builder()
            .endpointOverride(URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000)))
            .region(Region.US_EAST_1)
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(account[1], account[3])))
            .build();
    }

    private static void put(S3Client c, String bucket, String key) {
        c.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromString("bytes of " + key));
    }

    private static byte[] read(S3Client c, String bucket, String key) throws Exception {
        try (var in = c.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            return in.readAllBytes();
        }
    }

    private static void delete(S3Client c, String bucket, String key) {
        c.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    private static void denied(ThrowingRunnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(403));
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void noWorkerCredentialCanReplaceOrDeleteASealedArtifact() throws Exception {
        for (S3Client w : new S3Client[] {worker, recon}) {
            denied(() -> put(w, DER, sealedKey));
            denied(() -> delete(w, DER, sealedKey));
            denied(() -> put(w, DER, "sealed/" + tenant + "new-object"));
        }
        assertThat(new String(read(api, DER, sealedKey))).isEqualTo("bytes of " + sealedKey);
        // workers can still read sealed artifacts (the next stage's inputs) and write their own outputs
        assertThat(read(worker, DER, sealedKey)).isNotEmpty();
        assertThat(read(recon, DER, sealedKey)).isNotEmpty();
        put(worker, DER, workerKey);
        put(recon, DER, workerKey);
        // only the API account writes sealed copies
        put(api, DER, sealedKey);
    }

    @Test
    void workersCannotDeleteAnythingAndCannotWriteRawMedia() {
        for (S3Client w : new S3Client[] {worker, recon}) {
            denied(() -> delete(w, DER, workerKey));
            denied(() -> put(w, RAW, rawKey));
        }
    }

    @Test
    void thePostPrivacyAccountNeverReadsUnblurredMedia() throws Exception {
        denied(() -> read(recon, RAW, rawKey));
        denied(() -> read(recon, DER, piiKey));
        denied(() -> read(recon, DER, sealedPiiKey)); // the sealed copy keeps the pii/ segment, so the Deny still matches
        denied(() -> put(recon, DER, piiKey));
        assertThat(read(worker, RAW, rawKey)).isNotEmpty(); // the pre-privacy worker reads captures, as it must
        assertThat(read(worker, DER, piiKey)).isNotEmpty();
    }

    @Test
    void theBackupAccountOnlyReads() throws Exception {
        assertThat(read(backup, RAW, rawKey)).isNotEmpty();
        assertThat(read(backup, DER, sealedKey)).isNotEmpty();
        denied(() -> put(backup, DER, workerKey));
        denied(() -> delete(backup, DER, sealedKey));
    }

    @Test
    void anonymousRequestsGetNothing() throws Exception {
        for (String path : new String[] {"/" + DER + "/" + sealedKey, "/" + RAW + "/" + rawKey, "/" + DER + "?list-type=2"}) {
            HttpURLConnection c = (HttpURLConnection) URI.create("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000) + path)
                .toURL().openConnection();
            assertThat(c.getResponseCode()).as(path).isEqualTo(403);
            c.disconnect();
        }
    }
}
