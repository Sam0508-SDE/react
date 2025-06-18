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


