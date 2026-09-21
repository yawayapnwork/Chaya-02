package dev.chaya.api.health;

import java.sql.Connection;
import dev.chaya.api.storage.ObjectStore;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/** Checks real dependencies. PostgreSQL and object storage. */
@Service
public class HealthService {

    private final DataSource dataSource;
    private final ObjectStore storage;

    public HealthService(DataSource dataSource, ObjectStore storage) {
        this.dataSource = dataSource;
        this.storage = storage;
    }

    public HealthReport check() {
        boolean dbUp;
        try (Connection c = dataSource.getConnection()) {
            dbUp = c.isValid(2);
        } catch (Exception e) {
            dbUp = false;
        }
        boolean storageUp = storage.ping();
        return new HealthReport(dbUp && storageUp ? "UP" : "DOWN", dbUp ? "UP" : "DOWN", storageUp ? "UP" : "DOWN");
    }

    public record HealthReport(String status, String database, String storage) {}
}
