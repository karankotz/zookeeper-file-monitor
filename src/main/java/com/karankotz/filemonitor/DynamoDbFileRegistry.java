package com.karankotz.filemonitor;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.karankotz.filemonitor.FileRecord.FileStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

/**
 * Durable completion registry in DynamoDB. Answers "has this file already been processed?"
 *
 * <pre>
 * Table: FileRegistry   (partition key: fileId)
 * fileId | fileName | status | workerId | attempts | updatedAt | lineCount | error
 * </pre>
 *
 * Every state change is a conditional write, so the registry itself rejects stale
 * workers (see {@link #markCompleted}).
 */
public class DynamoDbFileRegistry {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbFileRegistry.class);

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDbFileRegistry(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    /**
     * With DYNAMODB_ENDPOINT set, talk to DynamoDB Local with dummy credentials;
     * otherwise use the default AWS credential chain (env vars, ~/.aws, IAM role).
     */
    public static DynamoDbClient createClient(Config config) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(config.awsRegion()));
        if (config.dynamoDbEndpoint() != null) {
            builder.endpointOverride(URI.create(config.dynamoDbEndpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        }
        return builder.build();
    }

    /** Create the table (on-demand billing) if it doesn't exist yet. */
    public void ensureTable() {
        try {
            dynamo.describeTable(r -> r.tableName(table));
            return;
        } catch (ResourceNotFoundException e) {
            log.info("Creating DynamoDB table {}", table);
        }
        try {
            dynamo.createTable(CreateTableRequest.builder()
                    .tableName(table)
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("fileId").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder()
                            .attributeName("fileId").keyType(KeyType.HASH).build())
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
        } catch (ResourceInUseException e) {
            // Another worker created it at the same time.
        }
        dynamo.waiter().waitUntilTableExists(r -> r.tableName(table));
    }

    public Optional<FileRecord> get(String fileId) {
        Map<String, AttributeValue> item = dynamo.getItem(r -> r.tableName(table)
                .key(key(fileId))
                .consistentRead(true)).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(toRecord(item));
    }

    public boolean isCompleted(String fileId) {
        return get(fileId).map(r -> r.status() == FileStatus.COMPLETED).orElse(false);
    }

    /**
     * Record that this worker started processing the file and bump the attempt counter.
     * Returns empty if the file is already COMPLETED.
     */
    public Optional<FileRecord> markProcessing(String fileId, String fileName, String workerId) {
        try {
            Map<String, AttributeValue> updated = dynamo.updateItem(r -> r.tableName(table)
                    .key(key(fileId))
                    .updateExpression("SET fileName = :name, #status = :processing, workerId = :me, "
                            + "updatedAt = :now, attempts = if_not_exists(attempts, :zero) + :one REMOVE #error")
                    .conditionExpression("attribute_not_exists(fileId) OR #status <> :completed")
                    .expressionAttributeNames(Map.of("#status", "status", "#error", "error"))
                    .expressionAttributeValues(Map.of(
                            ":name", s(fileName),
                            ":processing", s(FileStatus.PROCESSING.name()),
                            ":completed", s(FileStatus.COMPLETED.name()),
                            ":me", s(workerId),
                            ":now", s(Instant.now().toString()),
                            ":zero", n(0),
                            ":one", n(1)))
                    .returnValues(ReturnValue.ALL_NEW)).attributes();
            return Optional.of(toRecord(updated));
        } catch (ConditionalCheckFailedException e) {
            return Optional.empty();
        }
    }

    /**
     * Store the result and mark COMPLETED in a single conditional write. The condition
     * "workerId = me" is a fence: if this worker lost its ZooKeeper session and another
     * worker took the file over, this write is rejected and returns false.
     */
    public boolean markCompleted(String fileId, String workerId, long lineCount) {
        try {
            dynamo.updateItem(r -> r.tableName(table)
                    .key(key(fileId))
                    .updateExpression("SET #status = :completed, updatedAt = :now, lineCount = :lines")
                    .conditionExpression("#status = :processing AND workerId = :me")
                    .expressionAttributeNames(Map.of("#status", "status"))
                    .expressionAttributeValues(Map.of(
                            ":completed", s(FileStatus.COMPLETED.name()),
                            ":processing", s(FileStatus.PROCESSING.name()),
                            ":me", s(workerId),
                            ":now", s(Instant.now().toString()),
                            ":lines", n(lineCount))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /** Mark FAILED, fenced the same way as {@link #markCompleted}. */
    public boolean markFailed(String fileId, String workerId, String error) {
        try {
            dynamo.updateItem(r -> r.tableName(table)
                    .key(key(fileId))
                    .updateExpression("SET #status = :failed, updatedAt = :now, #error = :error")
                    .conditionExpression("#status = :processing AND workerId = :me")
                    .expressionAttributeNames(Map.of("#status", "status", "#error", "error"))
                    .expressionAttributeValues(Map.of(
                            ":failed", s(FileStatus.FAILED.name()),
                            ":processing", s(FileStatus.PROCESSING.name()),
                            ":me", s(workerId),
                            ":now", s(Instant.now().toString()),
                            ":error", s(error == null ? "unknown" : error))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    public List<FileRecord> findAll() {
        List<FileRecord> records = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            ScanRequest.Builder request = ScanRequest.builder().tableName(table).consistentRead(true);
            if (startKey != null) {
                request.exclusiveStartKey(startKey);
            }
            ScanResponse response = dynamo.scan(request.build());
            response.items().forEach(item -> records.add(toRecord(item)));
            startKey = response.hasLastEvaluatedKey() ? response.lastEvaluatedKey() : null;
        } while (startKey != null);
        return records;
    }

    private static FileRecord toRecord(Map<String, AttributeValue> item) {
        return new FileRecord(
                str(item, "fileId"),
                str(item, "fileName"),
                FileStatus.valueOf(str(item, "status")),
                str(item, "workerId"),
                item.containsKey("attempts") ? Integer.parseInt(item.get("attempts").n()) : 0,
                str(item, "updatedAt"),
                item.containsKey("lineCount") ? Long.parseLong(item.get("lineCount").n()) : null,
                str(item, "error"));
    }

    private static Map<String, AttributeValue> key(String fileId) {
        return Map.of("fileId", s(fileId));
    }

    private static String str(Map<String, AttributeValue> item, String name) {
        AttributeValue v = item.get(name);
        return v == null ? null : v.s();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }
}
