package dev.chaya.api.storage;

import java.io.InputStream;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/** S3-compatible implementation, used against MinIO (path-style addressing). */
@Component
public class S3ObjectStore implements ObjectStore {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStore.class);

    private final S3Client s3;
    private final String bucket;
    private volatile boolean bucketReady;

    public S3ObjectStore(StorageProperties props) {
        this.bucket = props.bucket();
        this.s3 = S3Client.builder()
            .endpointOverride(URI.create(props.endpoint()))
            .region(Region.of(props.region()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    }

    @Override
    public void ensureBucket() {
        if (bucketReady) {
            return;
        }
        try {
            try {
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (NoSuchBucketException e) {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                log.info("created bucket {}", bucket);
            }
            bucketReady = true;
        } catch (S3Exception | software.amazon.awssdk.core.exception.SdkClientException e) {
            throw new StorageException("object storage unavailable while preparing bucket " + bucket, e);
        }
    }

    @Override
    public String beginMultipart(String key, String contentType) {
        ensureBucket();
        return call("begin upload", () -> s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
            .bucket(bucket).key(key).contentType(contentType).build()).uploadId());
    }

    @Override
    public String uploadPart(String key, String uploadId, int partNumber, byte[] data) {
        return call("upload part", () -> s3.uploadPart(UploadPartRequest.builder()
            .bucket(bucket).key(key).uploadId(uploadId).partNumber(partNumber).contentLength((long) data.length).build(),
            RequestBody.fromBytes(data)).eTag());
    }

    @Override
    public void completeMultipart(String key, String uploadId, List<PartEtag> parts) {
        List<CompletedPart> sorted = parts.stream().sorted(Comparator.comparingInt(PartEtag::partNumber))
            .map(p -> CompletedPart.builder().partNumber(p.partNumber()).eTag(p.etag()).build()).toList();
        call("complete upload", () -> s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
            .bucket(bucket).key(key).uploadId(uploadId)
            .multipartUpload(CompletedMultipartUpload.builder().parts(sorted).build()).build()));
    }

    @Override
    public void abortMultipart(String key, String uploadId) {
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder().bucket(bucket).key(key).uploadId(uploadId).build());
        } catch (RuntimeException e) {
            log.warn("could not abort multipart upload for {}: {}", key, e.getMessage());
        }
    }

    @Override
    public long size(String key) {
        return call("stat object", () -> s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength());
    }

    @Override
    public InputStream open(String key) {
        return call("read object", () -> s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(),
            ResponseTransformer.toInputStream()));
    }

    @Override
    public void delete(String key) {
        call("delete object", () -> s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build()));
    }

    @Override
    public boolean ping() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (NoSuchBucketException e) {
            return true; // the service answered; the bucket is created on first upload
        } catch (RuntimeException e) {
            return false;
        }
    }

    private <T> T call(String what, java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (S3Exception | software.amazon.awssdk.core.exception.SdkClientException e) {
            throw new StorageException("object storage failed to " + what, e);
        }
    }
}
