package com.ep.data;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * data-platform Data Platform bootstrap class (D-088).
 *
 * <p>Component-scans com.ep.data and all sub-packages (aiops, nlquery, feature,
 * drift, metadata, lineage, profile, common, config).
 */
@SpringBootApplication
public class DataPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(DataPlatformApplication.class, args);
    }
}
