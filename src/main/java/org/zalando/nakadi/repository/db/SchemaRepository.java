package org.zalando.nakadi.repository.db;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.zalando.nakadi.domain.EventTypeSchema;
import org.zalando.nakadi.exceptions.NoSuchSchemaException;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Component
public class SchemaRepository extends AbstractDbRepository {

    @Autowired
    public SchemaRepository(final JdbcTemplate jdbcTemplate, final ObjectMapper objectMapper) {
        super(jdbcTemplate, objectMapper);
    }

    public List<EventTypeSchema> getSchemas(final String name, final int offset, final int limit) {
        return jdbcTemplate.query(
                "SELECT ets_schema_object FROM zn_data.event_type_schema " +
                       "WHERE ets_event_type_name = ? ORDER BY ets_schema_object->>'created_at' DESC LIMIT ? OFFSET ? ",
                new Object[]{name, limit, offset},
                new SchemaRowMapper());
    }

    public EventTypeSchema getSchemaVersion(final String name, final String version)
            throws NoSuchSchemaException {
        final String sql = "SELECT ets_schema_object FROM zn_data.event_type_schema " +
                "WHERE ets_event_type_name = ? AND ets_schema_object ->> 'version' = ?";

        try {
            return jdbcTemplate.queryForObject(sql, new Object[]{name, version}, new SchemaRowMapper());
        } catch (EmptyResultDataAccessException e) {
            throw new NoSuchSchemaException("EventType \"" + name
                    + "\" has no schema with version \"" + version + "\"", e);
        }
    }

    public int getSchemasCount(final String name) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM zn_data.event_type_schema WHERE ets_event_type_name = ?",
                new Object[]{name},
                Integer.class);
    }

    private final class SchemaRowMapper implements RowMapper<EventTypeSchema> {
        @Override
        public EventTypeSchema mapRow(final ResultSet rs, final int rowNum) throws SQLException {
            try {
                return jsonMapper.readValue(rs.getString("ets_schema_object"), EventTypeSchema.class);
            } catch (IOException e) {
                throw new SQLException(e);
            }
        }
    }
}
