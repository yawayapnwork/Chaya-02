package dev.chaya.api.health;

import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.stereotype.Service;

/** Checks real dependencies. Currently: PostgreSQL connectivity. */
@Service
public class HealthService {

    private final DataSource dataSource;

    public HealthService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public HealthReport check() {
        boolean dbUp;
        try (Connection c = dataSource.getConnection()) {
            dbUp = c.isValid(2);
        } catch (Exception e) {
            dbUp = false;
        }
        return new HealthReport(dbUp ? "UP" : "DOWN", dbUp ? "UP" : "DOWN");
    }

    public record HealthReport(String status, String database) {}
}
