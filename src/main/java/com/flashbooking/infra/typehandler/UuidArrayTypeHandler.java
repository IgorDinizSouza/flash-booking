package com.flashbooking.infra.typehandler;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/** UUID[] do PostgreSQL, para {@code WHERE id = ANY(#{ids})} (um unico parametro, sem IN dinamico). */
@MappedTypes(UUID[].class)
public class UuidArrayTypeHandler extends BaseTypeHandler<UUID[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID[] parameter, JdbcType jdbcType)
            throws SQLException {
        Array array = ps.getConnection().createArrayOf("uuid", parameter);
        try {
            ps.setArray(i, array);
        } finally {
            array.free();
        }
    }

    @Override
    public UUID[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toUuids(rs.getArray(columnName));
    }

    @Override
    public UUID[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toUuids(rs.getArray(columnIndex));
    }

    @Override
    public UUID[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toUuids(cs.getArray(columnIndex));
    }

    private static UUID[] toUuids(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        try {
            Object[] raw = (Object[]) array.getArray();
            UUID[] out = new UUID[raw.length];
            for (int i = 0; i < raw.length; i++) {
                out[i] = (UUID) raw[i];
            }
            return out;
        } finally {
            array.free();
        }
    }
}
