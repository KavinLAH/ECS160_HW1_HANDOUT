package com.hw1.persistence;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.ProxyFactory;
import javassist.util.proxy.ProxyObject;

public class SQLiteDB {
    private Connection connection;
    private SQLiteDBHelper dbHelper;

    public SQLiteDB(String dbPath) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        this.dbHelper = new SQLiteDBHelper(this.connection);
    }

    public void createTable(Class<?> clazz) throws SQLException {

        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append(clazz.getSimpleName()).append(" (");

        Field[] fields = clazz.getDeclaredFields();
        List<Field> persistableFields = new ArrayList<>();

        // Only include fields marked with @Persistable annotation
        for (Field field : fields) {
            if (field.isAnnotationPresent(Persistable.class)) {
                persistableFields.add(field);
            }
        }

        for (int i = 0; i < persistableFields.size(); i++) {
            Field field = persistableFields.get(i);
            String fieldName = dbHelper.camelToSnakeCase(field.getName());
            String sqlType = dbHelper.getSQLType(field.getType());

            sql.append(fieldName).append(" ").append(sqlType);

            // Check if field is marked as PrimaryKey
            if (field.isAnnotationPresent(PrimaryKey.class)) {
                sql.append(" PRIMARY KEY");
            }

            if (i < persistableFields.size() - 1) {
                sql.append(", ");
            }
        }

        sql.append(")");

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql.toString());
        }
    }

    public void droptTable(Class<?> clazz) throws SQLException {
        String sql = "DROP TABLE IF EXISTS " + clazz.getSimpleName();

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    /**
     * Insert the given object into the database using reflection.
     * Only fields annotated with @Persistable should be stored.
     * Check the handout for more details.
     */
    public void insertRow(Object obj) throws SQLException, IllegalAccessException {
        // Use reflection to get the class and its fields
        Class<?> clazz = obj.getClass();
        Field[] fields = clazz.getDeclaredFields();
        List<Field> persistableFields = new ArrayList<>();

        // Filter to only fields with @Persistable annotation
        for (Field f : fields) {
            if (f.isAnnotationPresent(Persistable.class)) {
                persistableFields.add(f);
            }
        }

        // Build the INSERT SQL dynamically from field names
        String tableName = clazz.getSimpleName();
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(tableName).append(" (");

        for (int i = 0; i < persistableFields.size(); i++) {
            Field field = persistableFields.get(i);
            String fieldName = dbHelper.camelToSnakeCase(field.getName());
            sql.append(fieldName);
            if (i < persistableFields.size() - 1) {
                sql.append(", ");
            }
        }
        sql.append(") VALUES (");

        // Add a ? placeholder for each field
        for (int i = 0; i < persistableFields.size(); i++) {
            sql.append("?");
            if (i < persistableFields.size() - 1) {
                sql.append(", ");
            }
        }
        sql.append(")");

        // Bind actual field values to the prepared statement using reflection
        try (PreparedStatement pstmt = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < persistableFields.size(); i++) {
                Field f = persistableFields.get(i);
                f.setAccessible(true);
                Object value = f.get(obj);
                pstmt.setObject(i + 1, value);
            }
            pstmt.executeUpdate();
        }
    }

    /**
     * Load a row from the database using reflection.
     * The object passed in should have its primary key field populated.
     * This method will load the other persistable fields from the database
     * and return a proxy object that lazy-loads fields annotated
     * with @RemoteLazyLoad.
     * Check the handout for more details.
     * 
     */
    public <T> T loadRow(T obj) throws SQLException, IllegalAccessException {
        Class<?> clazz = obj.getClass();
        Field[] fields = clazz.getDeclaredFields();
        Field id = null;

        // Find the field marked as PrimaryKey so we can query by it
        for (Field f : fields) {
            if (f.isAnnotationPresent(PrimaryKey.class)) {
                id = f;
                break;
            }
        }

        // Get the primary key value from the object the caller passed in
        id.setAccessible(true);
        Object value = id.get(obj);

        // Build a SELECT query: "SELECT * FROM Post WHERE cid = ?"
        String tableName = clazz.getSimpleName();
        String primaryKeyColumn = dbHelper.camelToSnakeCase(id.getName());

        StringBuilder sql = new StringBuilder("SELECT * FROM ");
        sql.append(tableName).append(" WHERE ");
        sql.append(primaryKeyColumn).append(" = ?");

        T proxy;
        try (PreparedStatement pstmt = connection.prepareStatement(sql.toString())) {
            pstmt.setObject(1, value);
            ResultSet rs = pstmt.executeQuery();

            // Return null if no row was found
            if (!rs.next()) {
                return null;
            }

            // Populate all @Persistable fields from the result set
            for (Field f : fields) {
                if (f.isAnnotationPresent(Persistable.class)) {
                    String columnName = dbHelper.camelToSnakeCase(f.getName());
                    Object columnValue = rs.getObject(columnName);
                    f.setAccessible(true);
                    f.set(obj, columnValue);
                }
            }
        }

        // Create a Javassist proxy that wraps our class
        // The proxy intercepts method calls so we can add lazy loading behavior
        ProxyFactory factory = new ProxyFactory();
        factory.setSuperclass(clazz);
        try {
            proxy = (T) factory.create(new Class<?>[0], new Object[0]);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to create proxy: " + e.getMessage());
        }

        // Copy all field values from the original object into the proxy
        // Skip static fields since they belong to the class, not the instance
        for (Field f : fields) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            f.setAccessible(true);
            Object obj_val = f.get(obj);
            f.set(proxy, obj_val);
        }

        // Track which RemoteLazyLoad fields we've already fetched
        // so we don't fetch it again on every getter call
        Set<String> loadedFields = new HashSet<>();

        // This handler intercepts every method call on the proxy
        MethodHandler handler = (self, thisMethod, proceed, args) -> {
            String methodName = thisMethod.getName();
            if (methodName.startsWith("get")) {
                // Get field name from getter: "getAuthorAvatar" maps to "authorAvatar"
                String fieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
                // Look up the field on the superclass (the real Post class, not the proxy)
                Field field = self.getClass().getSuperclass().getDeclaredField(fieldName);
                if (field.isAnnotationPresent(RemoteLazyLoad.class) && !loadedFields.contains(fieldName)) {
                    // First access is the field currently holds URL bytes from the DB
                    // Then need to fetch the actual content from that URL
                    field.setAccessible(true);
                    Object currentValue = field.get(self);
                    String url = new String((byte[]) currentValue);
                    byte[] content = dbHelper.fetchContentFromUrl(url);
                    // Replace the URL bytes with the fetched content
                    field.set(self, content);
                    loadedFields.add(fieldName);
                    return content;
                }
            }
            // For all other methods, just call the original method normally
            return proceed.invoke(self, args);
        };

        ((ProxyObject) proxy).setHandler(handler);

        return proxy;
    }

    public Connection getConnection() {
        return connection;
    }

    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
