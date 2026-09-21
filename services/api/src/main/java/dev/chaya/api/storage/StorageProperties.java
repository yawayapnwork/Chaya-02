package dev.chaya.api.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("chaya.storage")
public record StorageProperties(String endpoint, String region, String accessKey, String secretKey, String bucket) {

    public StorageProperties {
        for (String[] required : new String[][] {
            {endpoint, "S3_ENDPOINT"}, {accessKey, "S3_ACCESS_KEY"}, {secretKey, "S3_SECRET_KEY"}, {bucket, "S3_BUCKET_RAW"}}) {
            if (required[0] == null || required[0].isBlank()) {
                throw new IllegalStateException("object storage is not configured: " + required[1] + " must be set");
            }
        }
    }
}
