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




@Bean
@StepScope
public JdbcBatchItemWriter<Databaserow> writerMainTable(DataSource dataSource,
        @Value("#{jobParameters['tableName']}") String tableName,
        @Value("#{jobParameters['destinationColumn']}") String destinationColumn) {

    String baseTableName = tableName.toUpperCase().endsWith("_STAG")
            ? tableName.substring(0, tableName.length() - 5)
            : tableName;

    String mainUpdateQuery = String.format(
        "UPDATE %s SET %s = :newEncryptedData WHERE TRANSACTION_ID = :originalEncryptedData",
        baseTableName, destinationColumn
    );

    return new JdbcBatchItemWriterBuilder<Databaserow>()
            .sql(mainUpdateQuery)
            .itemSqlParameterSourceProvider(new BeanPropertyItemSqlParameterSourceProvider<>())
            .dataSource(dataSource)
            .build();
}

@Bean
@StepScope
public JdbcBatchItemWriter<Databaserow> writerFlagTable(DataSource dataSource,
        @Value("#{jobParameters['tableName']}") String tableName) {

    String flagUpdateQuery = String.format(
        "UPDATE %s SET MIGRATION_FLAG = :flag WHERE TRANSACTION_ID = :originalEncryptedData",
        tableName
    );

    return new JdbcBatchItemWriterBuilder<Databaserow>()
            .sql(flagUpdateQuery)
            .itemSqlParameterSourceProvider(new BeanPropertyItemSqlParameterSourceProvider<>())
            .dataSource(dataSource)
            .build();
}


 CompositeItemWriter<Databaserow> compositeWriter = new CompositeItemWriter<>();
    compositeWriter.setDelegates(List.of(writerMainTable, writerFlagTable));




@Bean
public Step callPrcTransCardStep(StepBuilderFactory stepBuilderFactory) {
    return stepBuilderFactory.get("callPrcTransCardStep")
            .tasklet((contribution, chunkContext) -> {
                jdbcTemplate.execute("CALL PRC_TRANS_CARD()");
                return RepeatStatus.FINISHED;
            }).build();
}


@Bean
public Step callPrcTransCardStep(JobRepository jobRepository,
                                 PlatformTransactionManager transactionManager) {
    return new StepBuilder("callPrcTransCardStep", jobRepository)
            .tasklet((contribution, chunkContext) -> {
                try (Connection conn = dataSource.getConnection();
                     CallableStatement cs = conn.prepareCall("{call PRC_TRANS_CARD()}")) {
                    cs.execute();
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to execute PRC_TRANS_CARD", e);
                }
                return RepeatStatus.FINISHED;
            }, transactionManager)
            .build();
}


//DTO 

public class TableConfigDTO {
    private String tableName;
    private String sourceColumn;
    private String destinationColumn;
    private String primaryKey;

    // getters/setters
}


//REPO
@Repository
public class TableConfigRepository {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public List<TableConfigDTO> findAllConfigs() {
        return jdbcTemplate.query("SELECT table_name, source_column, destination_column, primary_key FROM table_config",
                (rs, rowNum) -> {
                    TableConfigDTO dto = new TableConfigDTO();
                    dto.setTableName(rs.getString("table_name"));
                    dto.setSourceColumn(rs.getString("source_column"));
                    dto.setDestinationColumn(rs.getString("destination_column"));
                    dto.setPrimaryKey(rs.getString("primary_key"));
                    return dto;
                });
    }
}


//DASHBOARD

@GetMapping("/job")
public String showJobForm(Model model) {
    List<TableConfigDTO> configs = tableConfigRepository.findAllConfigs();

    // For dropdown
    model.addAttribute("tables", configs.stream()
        .map(TableConfigDTO::getTableName)
        .collect(Collectors.toList()));

    // For auto-filling values (map table → config)
    Map<String, TableConfigDTO> configMap = configs.stream()
        .collect(Collectors.toMap(TableConfigDTO::getTableName, Function.identity()));

    model.addAttribute("configMap", configMap);

    return "job-form";
}



public class TableConfigDTO {
    private String tableName;
    private String sourceColumn;
    private String destinationColumn;
    private String primaryKey;

    public TableConfigDTO(String tableName, String sourceColumn, String destinationColumn, String primaryKey) {
        this.tableName = tableName;
        this.sourceColumn = sourceColumn;
        this.destinationColumn = destinationColumn;
        this.primaryKey = primaryKey;
    }

    public String getTableName() { return tableName; }
    public String getSourceColumn() { return sourceColumn; }
    public String getDestinationColumn() { return destinationColumn; }
    public String getPrimaryKey() { return primaryKey; }
}
ObjectMapper objectMapper = new ObjectMapper();
String configJson = objectMapper.writeValueAsString(configMap);
model.addAttribute("configJson", configJson);




// ✅ Databaserow.java (map-based structure)
public class Databaserow {
    private final Map<String, String> data = new HashMap<>();

    public String get(String key) {
        return data.get(key);
    }

    public void set(String key, String value) {
        data.put(key, value);
    }

    public Map<String, String> getAll() {
        return data;
    }
}


// ✅ CustomRowMapper.java
public class CustomRowMapper implements RowMapper<Databaserow> {
    private final String columnNames;
    private final String primaryKey;
    private final StepExecution stepExecution;
    private static final Logger logger = LoggerFactory.getLogger(CustomRowMapper.class);

    public CustomRowMapper(String columnNames, String primaryKey, StepExecution stepExecution) {
        this.columnNames = columnNames;
        this.primaryKey = primaryKey;
        this.stepExecution = stepExecution;
    }

    @Override
    public Databaserow mapRow(ResultSet rs, int rowNum) throws SQLException {
        if (stepExecution != null && stepExecution.getJobExecution().isStopping()) {
            logger.warn("Stop signal detected. Exiting row mapping early at rowNum: {}", rowNum);
            return null;
        }

        Databaserow row = new Databaserow();
        for (String col : columnNames.split(",")) {
            String value = rs.getString(col.trim());
            row.set(col.trim(), value);
            logger.debug("Mapped column: {} = {}", col.trim(), value);
        }
        String pkVal = rs.getString(primaryKey);
        row.set(primaryKey, pkVal);
        logger.debug("Mapped primary key: {} = {}", primaryKey, pkVal);

        return row;
    }
}


// ✅ Processor.java
public class EncryptionProcessor implements ItemProcessor<Databaserow, Databaserow> {
    private final String sourceColumn;
    private final String destinationColumn;
    private static final Logger logger = LoggerFactory.getLogger(EncryptionProcessor.class);

    public EncryptionProcessor(String sourceColumn, String destinationColumn) {
        this.sourceColumn = sourceColumn;
        this.destinationColumn = destinationColumn;
    }

    @Override
    public Databaserow process(Databaserow row) throws Exception {
        logger.info("Processing row with PK: {}", row.get("ID")); // or appropriate primary key name

        if (!sourceColumn.contains(",")) {
            String raw = row.get(sourceColumn);
            if (raw != null && !raw.isBlank()) {
                String encrypted = encrypt(raw);
                row.set(destinationColumn, encrypted);
                logger.debug("Encrypted {} -> {}", raw, encrypted);
            }
        } else {
            String[] sourceCols = sourceColumn.split(",");
            String[] destCols = destinationColumn.split(",");

            if (sourceCols.length != destCols.length) {
                logger.error("Mismatch in source and destination columns: {} vs {}", sourceCols.length, destCols.length);
                throw new IllegalArgumentException("Mismatch in source and destination column count");
            }

            for (int i = 0; i < sourceCols.length; i++) {
                String src = sourceCols[i].trim();
                String dst = destCols[i].trim();

                String raw = row.get(src);
                if (raw != null && !raw.isBlank()) {
                    String encrypted = encrypt(raw);
                    row.set(dst, encrypted);
                    logger.debug("Encrypted {} -> {} for column {}", raw, encrypted, src);
                }
            }
        }

        return row;
    }

    private String encrypt(String value) {
        // 🔐 Your encryption logic here
        return "enc(" + value + ")";
    }
}


// ✅ WriterConfig.java
public JdbcBatchItemWriter<Databaserow> writer(String table, String destinationColumn, String primaryKey) {
    JdbcBatchItemWriter<Databaserow> writer = new JdbcBatchItemWriter<>();
    writer.setItemSqlParameterSourceProvider(new ItemSqlParameterSourceProvider<Databaserow>() {
        @Override
        public SqlParameterSource createSqlParameterSource(Databaserow item) {
            MapSqlParameterSource params = new MapSqlParameterSource();

            for (String col : destinationColumn.split(",")) {
                params.addValue(col.trim(), item.get(col.trim()));
            }
            params.addValue(primaryKey, item.get(primaryKey));
            return params;
        }
    });

    StringBuilder sql = new StringBuilder("UPDATE " + table + " SET ");
    String[] destCols = destinationColumn.split(",");
    for (int i = 0; i < destCols.length; i++) {
        if (i > 0) sql.append(", ");
        sql.append(destCols[i].trim()).append(" = :").append(destCols[i].trim());
    }
    sql.append(" WHERE ").append(primaryKey).append(" = :").append(primaryKey);

    logger.info("Generated SQL for writer: {}", sql);
    writer.setSql(sql.toString());
    writer.setDataSource(dataSource);
    writer.afterPropertiesSet();
    return writer;
}




@Override
public Map<String, String> process(Map<String, String> row) {
    try {
        String sourceColumn = row.get("sourceColumns");
        String destinationColumn = row.get("destinationColumns");

        if (!sourceColumn.contains(",")) {
            // 🟢 Single-column logic
            String raw = row.get(sourceColumn);
            if (raw != null && !raw.isBlank()) {
                String decrypted = new String(decryptAESGCNopadding(hexToByte(raw), oldKey.getBytes()));
                String encrypted = toHexString(encryptAESGCNopadding(decrypted.getBytes(), newKey.getBytes()));
                row.put(destinationColumn, encrypted);
            }
        } else {
            // 🟡 Multi-column logic
            String[] sourceCols = sourceColumn.split(",");
            String[] destCols = destinationColumn.split(",");

            if (sourceCols.length != destCols.length) {
                throw new IllegalArgumentException("Mismatch in source and destination column count");
            }

            for (int i = 0; i < sourceCols.length; i++) {
                String src = sourceCols[i].trim();
                String dst = destCols[i].trim();

                String raw = row.get(src);
                if (raw != null && !raw.isBlank()) {
                    String decrypted = new String(decryptAESGCNopadding(hexToByte(raw), oldKey.getBytes()));
                    String encrypted = toHexString(encryptAESGCNopadding(decrypted.getBytes(), newKey.getBytes()));
                    row.put(dst, encrypted);
                }
            }
        }

        row.put("conversion_status", "Y");

    } catch (Exception e) {
        row.put("conversion_status", "C");
    }

    // ✅ Preserve primary key from reader — no overwrite
    return row;
}



ItemProcessor<Map<String, String>, Map<String, String>> 
