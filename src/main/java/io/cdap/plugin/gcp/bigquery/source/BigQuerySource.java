/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.gcp.bigquery.source;

import com.google.auth.Credentials;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableDefinition.Type;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TimePartitioning;
import com.google.cloud.hadoop.io.bigquery.BigQueryConfiguration;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.api.data.batch.Input;
import io.cdap.cdap.api.data.batch.InputFormatProvider;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.dataset.lib.KeyValue;
import io.cdap.cdap.etl.api.Emitter;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import io.cdap.cdap.etl.api.StageConfigurer;
import io.cdap.cdap.etl.api.batch.BatchRuntimeContext;
import io.cdap.cdap.etl.api.batch.BatchSource;
import io.cdap.cdap.etl.api.batch.BatchSourceContext;
import io.cdap.cdap.etl.api.validation.ValidationFailure;
import io.cdap.plugin.common.LineageRecorder;
import io.cdap.plugin.gcp.bigquery.util.BigQueryConstants;
import io.cdap.plugin.gcp.bigquery.util.BigQueryUtil;
import io.cdap.plugin.gcp.common.GCPUtils;
import org.apache.avro.generic.GenericData;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Class description here.
 */
@Plugin(type = "batchsource")
@Name(BigQuerySource.NAME)
@Description("This source reads the entire contents of a BigQuery table. "
  + "BigQuery is Google's serverless, highly scalable, enterprise data warehouse."
  + "Data is first written to a temporary location on Google Cloud Storage, then read into the pipeline from there.")
public final class BigQuerySource extends BatchSource<LongWritable, GenericData.Record, StructuredRecord> {
  private static final Logger LOG = LoggerFactory.getLogger(BigQuerySource.class);
  public static final String NAME = "BigQueryTable";
  private BigQuerySourceConfig config;
  private Schema outputSchema;
  private Configuration configuration;
  private final BigQueryAvroToStructuredTransformer transformer = new BigQueryAvroToStructuredTransformer();
  // UUID for the run. Will be used as bucket name if bucket is not provided.
  private UUID uuid;

  @Override
  public void configurePipeline(PipelineConfigurer configurer) {
    super.configurePipeline(configurer);
    StageConfigurer stageConfigurer = configurer.getStageConfigurer();
    FailureCollector collector = stageConfigurer.getFailureCollector();
    config.validate(collector);
    Schema configuredSchema = config.getSchema(collector);

    // if any of the require properties have macros or the service account can't be auto-detected
    // or the dataset project isn't set and the project can't be auto-detected
    if (!config.canConnect() || (config.isServiceAccountFilePath() && config.autoServiceAccountUnavailable()) ||
      (config.tryGetProject() == null && config.getDatasetProject() == null)) {
      stageConfigurer.setOutputSchema(configuredSchema);
      return;
    }

    Schema schema = getSchema(collector);
    validatePartitionProperties(collector);

    if (configuredSchema == null) {
      stageConfigurer.setOutputSchema(schema);
      return;
    }

    validateConfiguredSchema(configuredSchema, collector);
    stageConfigurer.setOutputSchema(configuredSchema);
  }

  @Override
  public void prepareRun(BatchSourceContext context) throws Exception {
    FailureCollector collector = context.getFailureCollector();
    config.validate(collector);

    if (getBQSchema(collector).getFields().isEmpty()) {
      collector.addFailure(String.format("BigQuery table %s.%s does not have a schema.",
                                         config.getDataset(), config.getTable()),
                           "Please edit the table to add a schema.");
      collector.getOrThrowException();
    }

    Schema configuredSchema = getOutputSchema(collector);

    String serviceAccount = config.getServiceAccount();
    Credentials credentials = getCredentials(serviceAccount);
    BigQuery bigQuery = GCPUtils.getBigQuery(config.getDatasetProject(), credentials);

    uuid = UUID.randomUUID();
    String cmekKey = context.getArguments().get(GCPUtils.CMEK_KEY);
    configuration = BigQueryUtil.getBigQueryConfig(serviceAccount, config.getProject(), cmekKey,
                                                   config.getServiceAccountType());

    String bucket = config.getBucket();
    if (bucket == null) {
      bucket = uuid.toString();
      // By default, this option is false, meaning the job can not delete the bucket. So enable it only when bucket name
      // is not provided.
      configuration.setBoolean("fs.gs.bucket.delete.enable", true);

      // the dataset existence is validated before, so this cannot be null
      Dataset dataset = bigQuery.getDataset(config.getDataset());
      GCPUtils.createBucket(GCPUtils.getStorage(config.getProject(), credentials), bucket, dataset.getLocation(),
                            cmekKey);
    }

    configuration.set("fs.default.name", String.format("gs://%s/%s/", bucket, uuid));
    configuration.setBoolean("fs.gs.impl.disable.cache", true);
    configuration.setBoolean("fs.gs.metadata.cache.enable", false);

    if (config.getServiceAccount() != null) {
      configuration.set(BigQueryConstants.CONFIG_SERVICE_ACCOUNT, config.getServiceAccount());
      configuration.setBoolean(BigQueryConstants.CONFIG_SERVICE_ACCOUNT_IS_FILE, config.isServiceAccountFilePath());
    }
    if (config.getPartitionFrom() != null) {
      configuration.set(BigQueryConstants.CONFIG_PARTITION_FROM_DATE, config.getPartitionFrom());
    }
    if (config.getPartitionTo() != null) {
      configuration.set(BigQueryConstants.CONFIG_PARTITION_TO_DATE, config.getPartitionTo());
    }
    if (config.getFilter() != null) {
      configuration.set(BigQueryConstants.CONFIG_FILTER, config.getFilter());
    }
    if (config.getViewMaterializationProject() != null) {
      configuration.set(BigQueryConstants.CONFIG_VIEW_MATERIALIZATION_PROJECT, config.getViewMaterializationProject());
    }
    if (config.getViewMaterializationDataset() != null) {
      configuration.set(BigQueryConstants.CONFIG_VIEW_MATERIALIZATION_DATASET, config.getViewMaterializationDataset());
    }
    String temporaryTableName = String.format("_%s_%s", config.getTable(),
                                              UUID.randomUUID().toString().replaceAll("-", "_"));
    configuration.set(BigQueryConstants.CONFIG_TEMPORARY_TABLE_NAME, temporaryTableName);

    String temporaryGcsPath = String.format("gs://%s/%s/hadoop/input/%s", bucket, uuid, uuid);
    PartitionedBigQueryInputFormat.setTemporaryCloudStorageDirectory(configuration, temporaryGcsPath);
    BigQueryConfiguration.configureBigQueryInput(configuration, config.getDatasetProject(),
                                                 config.getDataset(), config.getTable());

    Job job = Job.getInstance(configuration);
    job.setOutputKeyClass(LongWritable.class);
    job.setOutputKeyClass(Text.class);

    // Both emitLineage and setOutputFormat internally try to create an external dataset if it does not already exists.
    // We call emitLineage before since it creates the dataset with schema.
    Type sourceTableType = config.getSourceTableType();
    emitLineage(context, configuredSchema, sourceTableType, config.getTable());
    setInputFormat(context);
  }

  @Override
  public void initialize(BatchRuntimeContext context) throws Exception {
    super.initialize(context);
    this.outputSchema = config.getSchema(context.getFailureCollector());
  }

  /**
   * Converts <code>JsonObject</code> to <code>StructuredRecord</code> for every record
   * retrieved from the BigQuery table.
   *
   * @param input   input record
   * @param emitter emitting the transformed record into downstream nodes.
   */
  @Override
  public void transform(KeyValue<LongWritable, GenericData.Record> input, Emitter<StructuredRecord> emitter)
    throws Exception {
    StructuredRecord transformed = outputSchema == null ?
      transformer.transform(input.getValue()) : transformer.transform(input.getValue(), outputSchema);
    emitter.emit(transformed);
  }

  @Override
  public void onRunFinish(boolean succeeded, BatchSourceContext context) {
    deleteGcsTemporaryDirectory();
    deleteBigQueryTemporaryTable();
  }

  private void deleteBigQueryTemporaryTable() {
    String temporaryTable = configuration.get(BigQueryConstants.CONFIG_TEMPORARY_TABLE_NAME);
    try {
      String serviceAccount = config.getServiceAccount();
      Credentials credentials = getCredentials(serviceAccount);
      BigQuery bigQuery = GCPUtils.getBigQuery(config.getDatasetProject(), credentials);
      bigQuery.delete(TableId.of(config.getProject(), config.getDataset(), temporaryTable));
      LOG.debug("Deleted temporary table '{}'", temporaryTable);
    } catch (IOException e) {
      LOG.error("Failed to load service account credentials: {}", e.getMessage(), e);
    }
  }

  private void deleteGcsTemporaryDirectory() {
    org.apache.hadoop.fs.Path gcsPath = null;
    String bucket = config.getBucket();
    if (bucket == null) {
      gcsPath = new Path(String.format("gs://%s", uuid));
    } else {
      gcsPath = new Path(String.format("gs://%s/%s", bucket, uuid));
    }
    try {
      FileSystem fs = gcsPath.getFileSystem(configuration);
      if (fs.exists(gcsPath)) {
        fs.delete(gcsPath, true);
        LOG.debug("Deleted temporary directory '{}'", gcsPath);
      }
    } catch (IOException e) {
      LOG.warn("Failed to delete temporary directory '{}': {}", gcsPath, e.getMessage());
    }
  }

  @Nullable
  private Credentials getCredentials(@Nullable String serviceAccount) throws IOException {
    return serviceAccount == null ?
            null : GCPUtils.loadServiceAccountCredentials(serviceAccount, config.isServiceAccountFilePath());
  }

  public Schema getSchema(FailureCollector collector) {
    com.google.cloud.bigquery.Schema bqSchema = getBQSchema(collector);
    return BigQueryUtil.getTableSchema(bqSchema, collector);
  }

  /**
   * Validate output schema. This is needed because its possible that output schema is set without using
   * {@link #getSchema} method.
   */
  private void validateConfiguredSchema(Schema configuredSchema, FailureCollector collector) {
    String dataset = config.getDataset();
    String tableName = config.getTable();
    String project = config.getDatasetProject();
    com.google.cloud.bigquery.Schema bqSchema = getBQSchema(collector);

    FieldList fields = bqSchema.getFields();

    // Match output schema field type with bigquery column type
    for (Schema.Field field : configuredSchema.getFields()) {
      try {
        Field bqField = fields.get(field.getName());
        ValidationFailure failure =
          BigQueryUtil.validateFieldSchemaMatches(bqField, field, dataset, tableName,
                                                  BigQuerySourceConfig.SUPPORTED_TYPES, collector);
        if (failure != null) {
          // For configured source schema field, failure will always map to output field in configured schema.
          failure.withOutputSchemaField(field.getName());
        }
      } catch (IllegalArgumentException e) {
        // this means that the field is not present in BigQuery table.
        collector.addFailure(
          String.format("Field '%s' is not present in table '%s:%s.%s'.", field.getName(), project, dataset, tableName),
          String.format("Remove field '%s' from the output schema.", field.getName()))
          .withOutputSchemaField(field.getName());
      }
    }
    collector.getOrThrowException();
  }

  private com.google.cloud.bigquery.Schema getBQSchema(FailureCollector collector) {
    String serviceAccount = config.getServiceAccount();
    String dataset = config.getDataset();
    String tableName = config.getTable();
    String project = config.getDatasetProject();

    Table table = BigQueryUtil.getBigQueryTable(project, dataset, tableName, serviceAccount,
                                                config.isServiceAccountFilePath(), collector);
    if (table == null) {
      // Table does not exist
      collector.addFailure(String.format("BigQuery table '%s:%s.%s' does not exist.", project, dataset, tableName),
                           "Ensure correct table name is provided.")
        .withConfigProperty(BigQuerySourceConfig.NAME_TABLE);
      throw collector.getOrThrowException();
    }

    com.google.cloud.bigquery.Schema bqSchema = table.getDefinition().getSchema();
    if (bqSchema == null) {
      collector.addFailure(String.format("Cannot read from table '%s:%s.%s' because it has no schema.",
                                         project, dataset, table), "Alter the table to have a schema.")
        .withConfigProperty(BigQuerySourceConfig.NAME_TABLE);
      throw collector.getOrThrowException();
    }
    return bqSchema;
  }

  @Nullable
  private Schema getOutputSchema(FailureCollector collector) {
    Schema outputSchema = config.getSchema(collector);
    outputSchema = outputSchema == null ? getSchema(collector) : outputSchema;
    validatePartitionProperties(collector);
    validateConfiguredSchema(outputSchema, collector);
    return outputSchema;
  }

  private void validatePartitionProperties(FailureCollector collector) {
    String project = config.getDatasetProject();
    String dataset = config.getDataset();
    String tableName = config.getTable();
    Table sourceTable = BigQueryUtil.getBigQueryTable(project, dataset, tableName, config.getServiceAccount(),
                                                      config.isServiceAccountFilePath(), collector);
    if (sourceTable == null) {
      return;
    }
    if (sourceTable.getDefinition() instanceof StandardTableDefinition) {
      TimePartitioning timePartitioning = ((StandardTableDefinition) sourceTable.getDefinition()).getTimePartitioning();
      if (timePartitioning == null) {
        return;
      }
    }
    String partitionFromDate = config.getPartitionFrom();
    String partitionToDate = config.getPartitionTo();

    if (partitionFromDate == null && partitionToDate == null) {
      return;
    }
    LocalDate fromDate = null;
    if (partitionFromDate != null) {
      try {
        fromDate = LocalDate.parse(partitionFromDate);
      } catch (DateTimeException ex) {
        collector.addFailure("Invalid partition from date format.",
                             "Ensure partition from date is of format 'yyyy-MM-dd'.")
          .withConfigProperty(BigQuerySourceConfig.NAME_PARTITION_FROM);
      }
    }
    LocalDate toDate = null;
    if (partitionToDate != null) {
      try {
        toDate = LocalDate.parse(partitionToDate);
      } catch (DateTimeException ex) {
        collector.addFailure("Invalid partition to date format.", "Ensure partition to date is of format 'yyyy-MM-dd'.")
          .withConfigProperty(BigQuerySourceConfig.NAME_PARTITION_TO);
      }
    }

    if (fromDate != null && toDate != null && fromDate.isAfter(toDate) && !fromDate.isEqual(toDate)) {
      collector.addFailure("'Partition From Date' must be before or equal 'Partition To Date'.", null)
        .withConfigProperty(BigQuerySourceConfig.NAME_PARTITION_FROM)
        .withConfigProperty(BigQuerySourceConfig.NAME_PARTITION_TO);
    }
  }

  private void setInputFormat(BatchSourceContext context) {
    context.setInput(Input.of(config.referenceName, new InputFormatProvider() {
      @Override
      public String getInputFormatClassName() {
        return PartitionedBigQueryInputFormat.class.getName();
      }

      @Override
      public Map<String, String> getInputFormatConfiguration() {
        Map<String, String> config = new HashMap<>();
        for (Map.Entry<String, String> entry : configuration) {
          config.put(entry.getKey(), entry.getValue());
        }
        return config;
      }
    }));
  }

  private void emitLineage(BatchSourceContext context, Schema schema, Type sourceTableType,
                           String table) {
    LineageRecorder lineageRecorder = new LineageRecorder(context, config.referenceName);
    lineageRecorder.createExternalDataset(schema);

    String type = "table";
    if (Type.VIEW == sourceTableType) {
      type = "view";
    } else if (Type.MATERIALIZED_VIEW == sourceTableType) {
      type = "materialized view";
    }

    if (schema.getFields() != null) {
      lineageRecorder.recordRead("Read", String.format("Read from BigQuery %s '%s'.", type, table),
                                 schema.getFields().stream().map(Schema.Field::getName).collect(Collectors.toList()));
    }
  }
}
