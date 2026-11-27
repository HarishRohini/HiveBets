package dev.hivebets.lakesink;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where bronze files go and how often they are flushed. */
@ConfigurationProperties("hivebets.lake")
public record LakeProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        int flushRows,
        long flushIntervalMs,
        int maxBufferedRows) {
}
