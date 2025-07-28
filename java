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



public class EncryptionProcessor implements ItemProcessor<Map<String, String>, Map<String, String>> {

    private final String oldKey;
    private final String newKey;
    private static final Logger log = LoggerFactory.getLogger(EncryptionProcessor.class);

    public EncryptionProcessor(String oldKey, String newKey) {
        this.oldKey = oldKey;
        this.newKey = newKey;
    }

    @Override
    public Map<String, String> process(Map<String, String> row) {
        try {
            String[] sourceCols = row.get("sourceColumns").split(",");
            String[] destCols = row.get("destinationColumns").split(",");

            for (int i = 0; i < sourceCols.length; i++) {
                String source = row.get(sourceCols[i].trim());
                String decrypted = new String(decryptAESGCMNopadding(hexToByte(source), oldKey.getBytes()));
                String encrypted = toHexString(encryptAESGCMNopadding(decrypted.getBytes(), newKey.getBytes()));
                row.put(destCols[i].trim(), encrypted);
            }

            row.put("conversion_status", "Y");
        } catch (Exception e) {
            log.warn("Encryption failed for row with primaryKey = {}. Marking as corrupted.",
                     row.get("primaryKey"), e);

            // In case of failure, just copy over the original source column data
            String[] sourceCols = row.get("sourceColumns").split(",");
            String[] destCols = row.get("destinationColumns").split(",");

            for (int i = 0; i < sourceCols.length; i++) {
                row.put(destCols[i].trim(), row.get(sourceCols[i].trim())); // put raw source into dest
            }

            row.put("conversion_status", "C");
        }

        return row;
    }


    String sql = "UPDATE " + tableName + " SET " +
             destinationColumnSql + ", migration_flag = :migrationFlag " +
             "WHERE " + primaryKeyColumn + " = :primaryKey";

writer.setItemSqlParameterSourceProvider(new ItemSqlParameterSourceProvider<>() {
    @Override
    public SqlParameterSource createSqlParameterSource(Map<String, String> item) {
        MapSqlParameterSource paramSource = new MapSqlParameterSource();

        for (Map.Entry<String, String> entry : item.entrySet()) {
            paramSource.addValue(entry.getKey(), entry.getValue());
        }

        // Alias "conversion_status" → "migrationFlag"
        paramSource.addValue("migrationFlag", item.get("conversion_status"));

        return paramSource;
    }
}); so this will set destinatin column vlaue to encrypted values we set in processor 



    @PostMapping("/decrypt")
public ResponseEntity<Map<String, String>> decrypt(@RequestBody Map<String, String> payload) {
    String encryptedData = payload.get("encryptedData");
    String keyType = payload.get("keyType");

    // Fetch latest job execution
    JobExecution latestExecution = jobExplorer.getJobInstances("yourJobName", 0, 1)
        .stream()
        .findFirst()
        .flatMap(id -> jobExplorer.getJobExecutions(id).stream().findFirst())
        .orElse(null);

    if (latestExecution == null) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("decryptedValue", "❌ No recent job found"));
    }

    String key = keyType.equalsIgnoreCase("old")
        ? latestExecution.getJobParameters().getString("oldKey")
        : latestExecution.getJobParameters().getString("newKey");

    try {
        byte[] decrypted = decryptAESGCMNoPadding(hexToByte(encryptedData), key.getBytes());
        return ResponseEntity.ok(Map.of("decryptedValue", new String(decrypted)));
    } catch (Exception e) {
        return ResponseEntity.ok(Map.of("decryptedValue", "❌ Decryption failed"));
    }
}


// Spring Boot Controller to handle the form submission
package com.example.excelupdater.controller;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.io.*;

@Controller
public class ExcelUpdateController {

    @PostMapping("/update")
    public RedirectView updateExcel(@RequestParam String key,
                                    @RequestParam String env,
                                    @RequestParam String value,
                                    @RequestParam String path) {
        try (FileInputStream fis = new FileInputStream(path);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            int envCol = -1;

            // Find column index for environment from header row
            Row header = sheet.getRow(0);
            for (Cell cell : header) {
                if (cell.getStringCellValue().trim().equalsIgnoreCase(env)) {
                    envCol = cell.getColumnIndex();
                    break;
                }
            }

            if (envCol == -1) {
                return redirectWithMessage("error", "Environment not found in header row");
            }

            boolean updated = false;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row != null) {
                    Cell keyCell = row.getCell(0);
                    if (keyCell != null && key.trim().equalsIgnoreCase(keyCell.getStringCellValue().trim())) {
                        Cell valueCell = row.getCell(envCol);
                        if (valueCell == null) valueCell = row.createCell(envCol);
                        valueCell.setCellValue(value);
                        updated = true;
                        break;
                    }
                }
            }

            if (!updated) {
                return redirectWithMessage("error", "Key not found in sheet");
            }

            // Save the file
            try (FileOutputStream fos = new FileOutputStream(path)) {
                workbook.write(fos);
            }

            return redirectWithMessage("success", "Excel updated successfully");

        } catch (IOException e) {
            return redirectWithMessage("error", "Excel error: " + e.getMessage());
        }
    }

    private RedirectView redirectWithMessage(String status, String msg) {
        RedirectView rv = new RedirectView("/");
        rv.addStaticAttribute("status", status);
        rv.addStaticAttribute("msg", msg);
        return rv;
    }

    @GetMapping("/")
    public String home() {
        return "index"; // index.html in src/main/resources/static
    }
}
