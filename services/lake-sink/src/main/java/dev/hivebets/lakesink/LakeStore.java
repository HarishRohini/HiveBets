package dev.hivebets.lakesink;

import java.net.URI;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** The S3-compatible object store (RustFS in compose). */
@Component
public class LakeStore {
    private static final Logger log = LoggerFactory.getLogger(LakeStore.class);
    private final S3Client s3;
    private final String bucket;
    private volatile boolean bucketReady;

    public LakeStore(LakeProperties p) {
        this.bucket = p.bucket();
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(p.endpoint()))
                .region(Region.of(p.region()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(p.accessKey(), p.secretKey())))
                .forcePathStyle(true)
                // S3-compatible stores do not all accept the SDK's default CRC checksums.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
    }

    public void ensureBucket() {
        if (bucketReady) return;
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            create();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) create(); else throw e;
        }
        bucketReady = true;
    }

    private void create() {
        log.info("creating lake bucket {}", bucket);
        s3.createBucket(b -> b.bucket(bucket));
    }

    public void put(String key, Path file) {
        ensureBucket();
        s3.putObject(b -> b.bucket(bucket).key(key).contentType("application/vnd.apache.parquet"), RequestBody.fromFile(file));
    }

    public S3Client client() {
        return s3;
    }

    public String bucket() {
        return bucket;
    }
}
