package dev.chaya.api.storage;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class StorageConfig {

    /** Raw uploads. The default ObjectStore. */
    @Bean
    @Primary
    ObjectStore rawObjectStore(StorageProperties props) {
        return new S3ObjectStore(props, props.bucket());
    }

    /** Pipeline outputs: frames, poses, reconstructions, logs. */
    @Bean
    @Qualifier("derived")
    ObjectStore derivedObjectStore(StorageProperties props) {
        return new S3ObjectStore(props, props.derivedBucket());
    }
}
