package dev.chaya.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Every table that carries tenant or version scope, and the database-level rules that keep rows inside it (ADR 0002,
 * docs/security.md "Tenant inventory"). Reads the real migrated schema, so a new table cannot quietly skip them. The
 * inventory it finds is written to target/tenant-inventory.md (docs/security.md's table is generated from it).
 */
class TenantSchemaInvariantTest extends AbstractIntegrationTest {

    /** Tables that deliberately carry venue_id without a composite (venue_id, organization_id) foreign key, and why. */
    static final Map<String, String> NO_VENUE_FK = Map.of(
        "audit_log", "append-only; a refused cross-tenant attempt is recorded in the caller's organization with venue_id NULL "
            + "and the attempted id in metadata, so venue_id cannot be a foreign key into the caller's organization");

    record Column(String table, String column, boolean nullable) {}

    private List<Column> columns(String name) {
        return jdbc.sql("""
                SELECT c.table_name, c.column_name, c.is_nullable = 'YES' AS nullable
                  FROM information_schema.columns c
                  JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                 WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE' AND c.column_name = :n
                   AND c.table_name <> 'flyway_schema_history'
                 ORDER BY c.table_name""").param("n", name)
            .query((rs, i) -> new Column(rs.getString(1), rs.getString(2), rs.getBoolean(3))).list();
    }

    /** Foreign keys of a table: referenced table -> the sets of local columns of each key. */
    private Map<String, List<Set<String>>> foreignKeys(String table) {
        Map<String, List<Set<String>>> out = new TreeMap<>();
        jdbc.sql("""
                SELECT con.conname, ref.relname AS referenced,
                       array_to_string(ARRAY(SELECT a.attname FROM unnest(con.conkey) k
                                             JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k), ',') AS cols
                  FROM pg_constraint con
                  JOIN pg_class rel ON rel.oid = con.conrelid
                  JOIN pg_class ref ON ref.oid = con.confrelid
                  JOIN pg_namespace n ON n.oid = rel.relnamespace
                 WHERE con.contype = 'f' AND n.nspname = 'public' AND rel.relname = :t""").param("t", table)
            .query((rs, i) -> Map.entry(rs.getString("referenced"), Set.of(rs.getString("cols").split(","))))
            .list().forEach(e -> out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue()));
        return out;
    }

    @Test
    void everyVenueScopedRowIsBoundToItsOrganizationByTheDatabase() throws Exception {
        List<Column> venue = columns("venue_id");
        List<Column> org = columns("organization_id");
        List<Column> version = columns("scan_version_id");
        Set<String> orgTables = new java.util.HashSet<>(org.stream().map(Column::table).toList());
        List<String> problems = new ArrayList<>();

        for (Column c : venue) {
            if (!orgTables.contains(c.table())) {
                problems.add(c.table() + ": venue_id without organization_id");
                continue;
            }
            if (NO_VENUE_FK.containsKey(c.table())) {
                continue;
            }
            boolean composite = foreignKeys(c.table()).values().stream().flatMap(List::stream)
                .anyMatch(cols -> cols.contains("venue_id") && cols.contains("organization_id"));
            if (!composite) {
                problems.add(c.table() + ": no foreign key over (venue_id, organization_id)");
            }
        }
        for (Column c : version) {
            boolean fk = foreignKeys(c.table()).getOrDefault("scan_version", List.of()).stream().anyMatch(cols -> cols.contains("scan_version_id"));
            if (!fk && !c.table().equals("scan_version")) {
                problems.add(c.table() + ": scan_version_id is not a foreign key to scan_version");
            }
        }

        StringBuilder md = new StringBuilder("| Table | organization_id | venue_id | scan_version_id |\n|---|---|---|---|\n");
        Set<String> all = new java.util.TreeSet<>();
        for (List<Column> l : List.of(venue, org, version)) l.forEach(c -> all.add(c.table()));
        for (String t : all) {
            md.append("| `").append(t).append("` | ").append(cell(org, t)).append(" | ").append(cell(venue, t)).append(" | ")
                .append(cell(version, t)).append(" |\n");
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/tenant-inventory.md"), md, StandardCharsets.UTF_8);

        assertThat(problems).as("tenant schema invariants (ADR 0002)").isEmpty();
        assertThat(all).contains("processing_artifact", "capture_media", "processing_job", "poi", "ar_anchor", "scan_version");
    }

    private static String cell(List<Column> cols, String table) {
        return cols.stream().filter(c -> c.table().equals(table)).findFirst()
            .map(c -> c.nullable() ? "nullable" : "NOT NULL").orElse("—");
    }

    @Test
    void anObjectKeyOutsideItsRowsTenantIsRejectedByTheDatabase() {
        Fixtures.Tree t = fx.tree();
        Fixtures.Tree other = fx.tree();
        UUID job = jdbc.sql("INSERT INTO processing_job (organization_id, venue_id, scan_id, stage) VALUES (:o, :v, :s, 'MEDIA_FILTER') RETURNING id")
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).query(UUID.class).single();
        String sql = """
            INSERT INTO processing_artifact (organization_id, venue_id, scan_id, job_id, stage, bucket, object_key, checksum_sha256,
                                             content_type, size_bytes, kind, sealed, worker_object_key)
            VALUES (:o, :v, :s, :j, 'MEDIA_FILTER', 'b', :k, :sha, 'application/json', 1, 'X', :sealed, :wk)""";
        String own = "org/" + t.org() + "/venue/" + t.venue() + "/";
        String theirs = "org/" + other.org() + "/venue/" + other.venue() + "/";
        java.util.function.BiFunction<String, String, Integer> insert = (key, workerKey) -> jdbc.sql(sql)
            .param("o", t.org()).param("v", t.venue()).param("s", t.scan()).param("j", job).param("k", key)
            .param("sha", "a".repeat(64)).param("sealed", workerKey != null).param("wk", workerKey).update();

        for (String[] bad : new String[][] {{theirs + "x", null}, {"x/" + own + "y", null}, {"sealed/" + theirs + "x", own + "x"},
                {"sealed/" + own + "x", theirs + "x"}, {own + "x", own + "y"}}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> insert.apply(bad[0], bad[1])).as(bad[0])
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThat(insert.apply(own + "ok-" + UUID.randomUUID(), null)).isEqualTo(1);
        assertThat(insert.apply("sealed/" + own + "ok-" + UUID.randomUUID(), own + "w")).isEqualTo(1);
    }
}
