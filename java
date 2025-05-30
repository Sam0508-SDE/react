/ 1. Extract last 5 unique table names from jobHistory
    LinkedHashSet<String> tableNames = new LinkedHashSet<>();
    for (Map<String, Object> item : instanceList) {
        if (item.containsKey("tableName")) {
            tableNames.add((String) item.get("tableName"));
        }
        if (tableNames.size() >= 5) break;
    }

    // 2. Build tableStats map
    Map<String, Map<String, Integer>> tableStats = new LinkedHashMap<>();
    for (String table : tableNames) {
        int converted = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE converted_flag = 'Y'", Integer.class);
        int total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table, Integer.class);
        tableStats.put(table, Map.of(
            "converted", converted,
            "remaining", total - converted
        ));
    }
String updateQuery = String.format("""
    BEGIN
      UPDATE %s
         SET %s = :newEncryptedData
       WHERE %s = :originalEncryptedData
         AND ROWNUM = 1;

      UPDATE %s
         SET MIGRATION_FLAG = :flag
       WHERE %s = :originalEncryptedData
         AND ROWNUM = 1
         AND MIGRATION_FLAG NOT IN ('Y', 'C');
    END;
""", baseTableName, destinationColumn, sourceColumn, tableName, sourceColumn);
