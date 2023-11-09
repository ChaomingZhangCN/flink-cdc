/*
 * Copyright 2023 Ververica Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.connectors.mysql.debezium.reader;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.types.DataType;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkRuntimeException;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.ververica.cdc.connectors.mysql.debezium.DebeziumUtils;
import com.ververica.cdc.connectors.mysql.debezium.task.context.StatefulTaskContext;
import com.ververica.cdc.connectors.mysql.source.MySqlSourceTestBase;
import com.ververica.cdc.connectors.mysql.source.assigners.MySqlSnapshotSplitAssigner;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceConfig;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceConfigFactory;
import com.ververica.cdc.connectors.mysql.source.split.MySqlSplit;
import com.ververica.cdc.connectors.mysql.source.split.SourceRecords;
import com.ververica.cdc.connectors.mysql.testutils.RecordsFormatter;
import com.ververica.cdc.connectors.mysql.testutils.UniqueDatabase;
import io.debezium.connector.mysql.MySqlConnection;
import io.debezium.connector.mysql.MySqlPartition;
import io.debezium.data.Envelope;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.relational.TableId;
import io.debezium.schema.DataCollectionSchema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Tests for {@link SnapshotSplitReader}. */
public class SnapshotSplitReaderTest extends MySqlSourceTestBase {

    private static final UniqueDatabase customerDatabase =
            new UniqueDatabase(MYSQL_CONTAINER, "customer", "mysqluser", "mysqlpw");

    private static final UniqueDatabase customer3_0Database =
            new UniqueDatabase(MYSQL_CONTAINER, "customer3.0", "mysqluser", "mysqlpw");

    private static BinaryLogClient binaryLogClient;
    private static MySqlConnection mySqlConnection;

    @BeforeClass
    public static void init() {
        customerDatabase.createAndInitialize();
        customer3_0Database.createAndInitialize();
        MySqlSourceConfig sourceConfig =
                getConfig(customerDatabase, new String[] {"customers"}, 10);
        binaryLogClient = DebeziumUtils.createBinaryClient(sourceConfig.getDbzConfiguration());
        mySqlConnection = DebeziumUtils.createMySqlConnection(sourceConfig);
    }

    @AfterClass
    public static void afterClass() throws Exception {
        if (mySqlConnection != null) {
            mySqlConnection.close();
        }

        if (binaryLogClient != null) {
            binaryLogClient.disconnect();
        }
    }

    @Test
    public void testReadSingleSnapshotSplit() throws Exception {
        MySqlSourceConfig sourceConfig =
                getConfig(customerDatabase, new String[] {"customers_even_dist"}, 4);
        StatefulTaskContext statefulTaskContext =
                new StatefulTaskContext(sourceConfig, binaryLogClient, mySqlConnection);
        final DataType dataType =
                DataTypes.ROW(
                        DataTypes.FIELD("id", DataTypes.BIGINT()),
                        DataTypes.FIELD("name", DataTypes.STRING()),
                        DataTypes.FIELD("address", DataTypes.STRING()),
                        DataTypes.FIELD("phone_number", DataTypes.STRING()));
        List<MySqlSplit> mySqlSplits = getMySqlSplits(sourceConfig);

        String[] expected =
                new String[] {
                    "+I[101, user_1, Shanghai, 123567891234]",
                    "+I[102, user_2, Shanghai, 123567891234]",
                    "+I[103, user_3, Shanghai, 123567891234]",
                    "+I[104, user_4, Shanghai, 123567891234]"
                };
        List<String> actual =
                readTableSnapshotSplits(mySqlSplits, statefulTaskContext, 1, dataType);
        assertEqualsInAnyOrder(Arrays.asList(expected), actual);
    }

    @Test
    public void testReadSingleSnapshotSplitWithDotName() throws Exception {
        MySqlSourceConfig sourceConfig =
                getConfig(customer3_0Database, new String[] {"customers3.0"}, 4);
        BinaryLogClient binaryLogClient =
                DebeziumUtils.createBinaryClient(sourceConfig.getDbzConfiguration());
        MySqlConnection mySqlConnection = DebeziumUtils.createMySqlConnection(sourceConfig);
        StatefulTaskContext statefulTaskContext =
                new StatefulTaskContext(sourceConfig, binaryLogClient, mySqlConnection);
        final DataType dataType =
                DataTypes.ROW(
                        DataTypes.FIELD("id", DataTypes.BIGINT()),
                        DataTypes.FIELD("name", DataTypes.STRING()),
                        DataTypes.FIELD("address", DataTypes.STRING()),
                        DataTypes.FIELD("phone_number", DataTypes.STRING()));
        List<MySqlSplit> mySqlSplits =
                getMySqlSplits(
                        sourceConfig,
                        Arrays.asList(
                                        String.format(
                                                "`%s`.`customers3.0`",
                                                customer3_0Database.getDatabaseName()))
                                .stream()
                                .map(TableId::parse)
                                .collect(Collectors.toList()));

        String[] expected =
                new String[] {
                    "+I[101, user_1, Shanghai, 123567891234]",
                    "+I[102, user_2, Shanghai, 123567891234]",
                    "+I[103, user_3, Shanghai, 123567891234]",
                    "+I[104, user_4, Shanghai, 123567891234]"
                };
        List<String> actual =
                readTableSnapshotSplits(mySqlSplits, statefulTaskContext, 1, dataType);
        assertEqualsInAnyOrder(Arrays.asList(expected), actual);
    }

    @Test
    public void testReadAllSnapshotSplitsForOneTable() throws Exception {
        MySqlSourceConfig sourceConfig =
                getConfig(customerDatabase, new String[] {"customers_even_dist"}, 4);
        StatefulTaskContext statefulTaskContext =
                new StatefulTaskContext(sourceConfig, binaryLogClient, mySqlConnection);

        final DataType dataType =
                DataTypes.ROW(
                        DataTypes.FIELD("id", DataTypes.BIGINT()),
                        DataTypes.FIELD("name", DataTypes.STRING()),
                        DataTypes.FIELD("address", DataTypes.STRING()),
                        DataTypes.FIELD("phone_number", DataTypes.STRING()));
        List<MySqlSplit> mySqlSplits = getMySqlSplits(sourceConfig);

        String[] expected =
                new String[] {
                    "+I[101, user_1, Shanghai, 123567891234]",
                    "+I[102, user_2, Shanghai, 123567891234]",
                    "+I[103, user_3, Shanghai, 123567891234]",
                    "+I[104, user_4, Shanghai, 123567891234]",
                    "+I[105, user_5, Shanghai, 123567891234]",
                    "+I[106, user_6, Shanghai, 123567891234]",
                    "+I[107, user_7, Shanghai, 123567891234]",
                    "+I[108, user_8, Shanghai, 123567891234]",
                    "+I[109, user_9, Shanghai, 123567891234]",
                    "+I[110, user_10, Shanghai, 123567891234]"
                };
        List<String> actual =
                readTableSnapshotSplits(
                        mySqlSplits, statefulTaskContext, mySqlSplits.size(), dataType);
        assertEqualsInAnyOrder(Arrays.asList(expected), actual);
    }

    @Test
    public void testReadAllSplitForTableWithSingleLine() throws Exception {
        MySqlSourceConfig sourceConfig =
                getConfig(customerDatabase, new String[] {"customer_card_single_line"}, 10);
        StatefulTaskContext statefulTaskContext =
                new StatefulTaskContext(sourceConfig, binaryLogClient, mySqlConnection);

        final DataType dataType =
                DataTypes.ROW(
                        DataTypes.FIELD("card_no", DataTypes.BIGINT()),
                        DataTypes.FIELD("level", DataTypes.STRING()),
                        DataTypes.FIELD("name", DataTypes.STRING()),
                        DataTypes.FIELD("note", DataTypes.STRING()));
        List<MySqlSplit> mySqlSplits = getMySqlSplits(sourceConfig);
        String[] expected = new String[] {"+I[20001, LEVEL_1, user_1, user with level 1]"};
        List<String> actual =
                readTableSnapshotSplits(
                        mySqlSplits, statefulTaskContext, mySqlSplits.size(), dataType);
        assertEqualsInAnyOrder(Arrays.asList(expected), actual);
    }

    @Test
    public void testReadAllSnapshotSplitsForTables() throws Exception {
        MySqlSourceConfig sourceConfig =
                getConfig(
                        customerDatabase,
                        new String[] {"customer_card", "customer_card_single_line"},
                        10);
        StatefulTaskContext statefulTaskContext =
                new StatefulTaskContext(sourceConfig, binaryLogClient, mySqlConnection);

        DataType dataType =
                DataTypes.ROW(
                        DataTypes.FIELD("card_no", DataTypes.BIGINT()),
                        DataTypes.FIELD("level", DataTypes.STRING()),
                        DataTypes.FIELD("name", DataTypes.STRING()),
                        DataTypes.FIELD("note", DataTypes.STRING()));
        List<MySqlSplit> mySqlSplits = getMySqlSplits(sourceConfig);

        String[] expected =
                new String[] {
                    "+I[20001, LEVEL_1, user_1, user with level 1]",
                    "+I[20001, LEVEL_4, user_1, user with level 4]",
                    "+I[20002, LEVEL_4, user_2, user with level 4]",
                    "+I[20003, LEVEL_4, user_3, user with level 4]",
                    "+I[20004, LEVEL_1, user_4, user with level 4]",
                    "+I[20004, LEVEL_2, user_4, user with level 4]",
                    "+I[20004, LEVEL_3, user_4, user with level 4]",
                    "+I[20004, LEVEL_4, user_4, user with level 4]",
                    "+I[30006, LEVEL_3, user_5, user with level 3]",
                    "+I[30007, LEVEL_3, user_6, user with level 3]",
                    "+I[30008, LEVEL_3, user_7, user with level 3]",
                    "+I[30009, LEVEL_1, user_8, user with level 3]",
                    "+I[30009, LEVEL_2, user_8, user with level 3]",
                    "+I[30009, LEVEL_3, user_8, user with level 3]",
                    "+I[40001, LEVEL_2, user_9, user with level 2]",
                    "+I[40002, LEVEL_2, user_10, user with level 2]",
                    "+I[40003, LEVEL_2, user_11, user with level 2]",
                    "+I[50001, LEVEL_1, user_12, user with level 1]",
                    "+I[50002, LEVEL_1, user_13, user with level 1]",
                    "+I[50003, LEVEL_1, user_14, user with level 1]"
                };
        List<String> actual =
                readTableSnapshotSplits(
                        mySqlSplits, statefulTaskContext, mySqlSplits.size(), dataType);
        assertEqualsInAnyOrder(Arrays.asList(expected), actual);
    }

    @Test
    public void testThrowRuntimeExceptionInSnapshotScan() throws Exception {
        MySqlSourceConfig sourceConfig =
                getConfig(customerDatabase, new String[] {"customer_card", "customers_1"}, 10);
        StatefulTaskContext statefulTaskContext =
                new StatefulTaskContext(sourceConfig, binaryLogClient, mySqlConnection);

        DataType dataType =
                DataTypes.ROW(
                        DataTypes.FIELD("card_no", DataTypes.BIGINT()),
                        DataTypes.FIELD("level", DataTypes.STRING()),
                        DataTypes.FIELD("name", DataTypes.STRING()),
                        DataTypes.FIELD("note", DataTypes.STRING()));
        List<MySqlSplit> mySqlSplits = getMySqlSplits(sourceConfig);

        // DROP one table to mock snapshot scan error
        String tableToDrop = String.format("%s.customers_1", customerDatabase.getDatabaseName());
        mySqlConnection.execute("DROP TABLE IF EXISTS " + tableToDrop);
        mySqlConnection.commit();

        String exceptionMessage = String.format("Snapshotting of table %s failed.", tableToDrop);
        try {
            readTableSnapshotSplits(mySqlSplits, statefulTaskContext, mySqlSplits.size(), dataType);
            fail("Should fail.");
        } catch (Exception e) {
            assertTrue(e instanceof FlinkRuntimeException);
            assertTrue(ExceptionUtils.findThrowableWithMessage(e, exceptionMessage).isPresent());
        }
    }

    @Test
    public void testChangingDataInSnapshotScan() throws Exception {
        String tableName = "customers_even_dist";
        MySqlSourceConfig sourceConfig = getConfig(customerDatabase, new String[] {tableName}, 10);

        String tableId = customerDatabase.getDatabaseName() + "." + tableName;
        String[] changingDataSql =
                new String[] {
                    "UPDATE " + tableId + " SET address = 'Hangzhou' where id = 103",
                    "DELETE FROM " + tableId + " where id = 102",
                    "INSERT INTO " + tableId + " VALUES(102, 'user_2','Shanghai','123567891234')",
                    "UPDATE " + tableId + " SET address = 'Shanghai' where id = 103"
                };

        StatefulTaskContext statefulTaskContext =
                new MakeBinlogEventTaskContext(
                        sourceConfig,
                        binaryLogClient,
                        mySqlConnection,
                        () -> executeSql(sourceConfig, changingDataSql));

        final DataType dataType =
                DataTypes.ROW(
                        DataTypes.FIELD("id", DataTypes.BIGINT()),
                        DataTypes.FIELD("name", DataTypes.STRING()),
                        DataTypes.FIELD("address", DataTypes.STRING()),
                        DataTypes.FIELD("phone_number", DataTypes.STRING()));
        List<MySqlSplit> mySqlSplits = getMySqlSplits(sourceConfig);

        String[] expected =
                new String[] {
                    "+I[101, user_1, Shanghai, 123567891234]",
                    "+I[102, user_2, Shanghai, 123567891234]",
                    "+I[103, user_3, Shanghai, 123567891234]",
                    "+I[104, user_4, Shanghai, 123567891234]",
                    "+I[105, user_5, Shanghai, 123567891234]",
                    "+I[106, user_6, Shanghai, 123567891234]",
                    "+I[107, user_7, Shanghai, 123567891234]",
                    "+I[108, user_8, Shanghai, 123567891234]",
                    "+I[109, user_9, Shanghai, 123567891234]",
                    "+I[110, user_10, Shanghai, 123567891234]"
                };

        List<String> actual =
                readTableSnapshotSplits(
                        mySqlSplits, statefulTaskContext, mySqlSplits.size(), dataType);
        assertEqualsInAnyOrder(Arrays.asList(expected), actual);
    }

    @Test
    public void testInsertDataInSnapshotScan() throws Exception {
        String tableName = "customers_even_dist";
        MySqlSourceConfig sourceConfig = getConfig(customerDatabase, new String[] {tableName}, 10);

        String tableId = customerDatabase.getDatabaseName() + "." + tableName;
        String[] insertDataSql =
                new String[] {
                    "INSERT INTO " + tableId + " VALUES(111, 'user_11','Shanghai','123567891234')",
                    "INSERT INTO " + tableId + " VALUES(112, 'user_12','Shanghai','123567891234')",
                };

        String[] recoveryDataSql =
                new String[] {
                    "DELETE FROM " + tableId + " where id = 111",
                    "DELETE FROM " + tableId + " where id = 112",
                };

        StatefulTaskContext statefulTaskContext =
                new MakeBinlogEventTaskContext(
                        sourceConfig,
                        binaryLogClient,
                        mySqlConnection,
                        () -> executeSql(sourceConfig, insertDataSql));

        final DataType dataType =
                DataTypes.ROW(
                        DataTypes.FIELD("id", DataTypes.BIGINT()),
                        DataTypes.FIELD("name", DataTypes.STRING()),
                        DataTypes.FIELD("address", DataTypes.STRING()),
                        DataTypes.FIELD("phone_number", DataTypes.STRING()));
        List<MySqlSplit> mySqlSplits = getMySqlSplits(sourceConfig);

        String[] expected =
                new String[] {
                    "+I[101, user_1, Shanghai, 123567891234]",
                    "+I[102, user_2, Shanghai, 123567891234]",
                    "+I[103, user_3, Shanghai, 123567891234]",
                    "+I[104, user_4, Shanghai, 123567891234]",
                    "+I[105, user_5, Shanghai, 123567891234]",
                    "+I[106, user_6, Shanghai, 123567891234]",
                    "+I[107, user_7, Shanghai, 123567891234]",
                    "+I[108, user_8, Shanghai, 123567891234]",
                    "+I[109, user_9, Shanghai, 123567891234]",
                    "+I[110, user_10, Shanghai, 123567891234]",
                    "+I[111, user_11, Shanghai, 123567891234]",
                    "+I[112, user_12, Shanghai, 123567891234]"
                };

        List<String> actual =
                readTableSnapshotSplits(
                        mySqlSplits, statefulTaskContext, mySqlSplits.size(), dataType);
        assertEqualsInAnyOrder(Arrays.asList(expected), actual);
        executeSql(sourceConfig, recoveryDataSql);
    }

    @Test
    public void testDeleteDataInSnapshotScan() throws Exception {
        String tableName = "customers_even_dist";
        MySqlSourceConfig sourceConfig = getConfig(customerDatabase, new String[] {tableName}, 10);

        String tableId = customerDatabase.getDatabaseName() + "." + tableName;
        String[] deleteDataSql =
                new String[] {
                    "DELETE FROM " + tableId + " where id = 101",
                    "DELETE FROM " + tableId + " where id = 102",
                };

        String[] recoveryDataSql =
                new String[] {
                    "INSERT INTO " + tableId + " VALUES(101, 'user_1','Shanghai','123567891234')",
                    "INSERT INTO " + tableId + " VALUES(102, 'user_2','Shanghai','123567891234')",
                };

        StatefulTaskContext statefulTaskContext =
                new MakeBinlogEventTaskContext(
                        sourceConfig,
                        binaryLogClient,
                        mySqlConnection,
                        () -> executeSql(sourceConfig, deleteDataSql));

        final DataType dataType =
                DataTypes.ROW(
                        DataTypes.FIELD("id", DataTypes.BIGINT()),
                        DataTypes.FIELD("name", DataTypes.STRING()),
                        DataTypes.FIELD("address", DataTypes.STRING()),
                        DataTypes.FIELD("phone_number", DataTypes.STRING()));
        List<MySqlSplit> mySqlSplits = getMySqlSplits(sourceConfig);

        String[] expected =
                new String[] {
                    "+I[103, user_3, Shanghai, 123567891234]",
                    "+I[104, user_4, Shanghai, 123567891234]",
                    "+I[105, user_5, Shanghai, 123567891234]",
                    "+I[106, user_6, Shanghai, 123567891234]",
                    "+I[107, user_7, Shanghai, 123567891234]",
                    "+I[108, user_8, Shanghai, 123567891234]",
                    "+I[109, user_9, Shanghai, 123567891234]",
                    "+I[110, user_10, Shanghai, 123567891234]",
                };

        List<String> actual =
                readTableSnapshotSplits(
                        mySqlSplits, statefulTaskContext, mySqlSplits.size(), dataType);
        assertEqualsInAnyOrder(Arrays.asList(expected), actual);
        executeSql(sourceConfig, recoveryDataSql);
    }

    private List<String> readTableSnapshotSplits(
            List<MySqlSplit> mySqlSplits,
            StatefulTaskContext statefulTaskContext,
            int scanSplitsNum,
            DataType dataType)
            throws Exception {
        SnapshotSplitReader snapshotSplitReader = new SnapshotSplitReader(statefulTaskContext, 0);

        List<SourceRecord> result = new ArrayList<>();
        for (int i = 0; i < scanSplitsNum; i++) {
            MySqlSplit sqlSplit = mySqlSplits.get(i);
            if (snapshotSplitReader.isFinished()) {
                snapshotSplitReader.submitSplit(sqlSplit);
            }
            Iterator<SourceRecords> res;
            while ((res = snapshotSplitReader.pollSplitRecords()) != null) {
                while (res.hasNext()) {
                    SourceRecords sourceRecords = res.next();
                    result.addAll(sourceRecords.getSourceRecordList());
                }
            }
        }

        snapshotSplitReader.close();

        assertNotNull(snapshotSplitReader.getExecutorService());
        assertTrue(snapshotSplitReader.getExecutorService().isTerminated());

        return formatResult(result, dataType);
    }

    private List<String> formatResult(List<SourceRecord> records, DataType dataType) {
        final RecordsFormatter formatter = new RecordsFormatter(dataType);
        return formatter.format(records);
    }

    private List<MySqlSplit> getMySqlSplits(MySqlSourceConfig sourceConfig) {
        List<TableId> remainingTables =
                sourceConfig.getTableList().stream()
                        .map(TableId::parse)
                        .collect(Collectors.toList());
        return getMySqlSplits(sourceConfig, remainingTables);
    }

    private List<MySqlSplit> getMySqlSplits(
            MySqlSourceConfig sourceConfig, List<TableId> remainingTables) {
        final MySqlSnapshotSplitAssigner assigner =
                new MySqlSnapshotSplitAssigner(
                        sourceConfig, DEFAULT_PARALLELISM, remainingTables, false);
        assigner.open();
        List<MySqlSplit> mySqlSplitList = new ArrayList<>();
        while (true) {
            Optional<MySqlSplit> mySqlSplit = assigner.getNext();
            if (mySqlSplit.isPresent()) {
                mySqlSplitList.add(mySqlSplit.get());
            } else {
                break;
            }
        }
        assigner.close();
        return mySqlSplitList;
    }

    public static MySqlSourceConfig getConfig(
            UniqueDatabase database, String[] captureTables, int splitSize) {
        String[] captureTableIds =
                Arrays.stream(captureTables)
                        .map(tableName -> database.getDatabaseName() + "." + tableName)
                        .toArray(String[]::new);

        return new MySqlSourceConfigFactory()
                .databaseList(database.getDatabaseName())
                .tableList(captureTableIds)
                .serverId("1001-1002")
                .hostname(MYSQL_CONTAINER.getHost())
                .port(MYSQL_CONTAINER.getDatabasePort())
                .username(database.getUsername())
                .splitSize(splitSize)
                .fetchSize(2)
                .password(database.getPassword())
                .createConfig(0);
    }

    private boolean executeSql(MySqlSourceConfig sourceConfig, String[] sqlStatements) {
        try (JdbcConnection connection = DebeziumUtils.openJdbcConnection(sourceConfig)) {
            connection.setAutoCommit(false);
            connection.execute(sqlStatements);
            connection.commit();
        } catch (SQLException e) {
            LOG.error("Failed to execute sql statements.", e);
            return false;
        }
        return true;
    }

    static class MakeBinlogEventTaskContext extends StatefulTaskContext {

        private final Supplier<Boolean> makeBinlogFunction;

        public MakeBinlogEventTaskContext(
                MySqlSourceConfig sourceConfig,
                BinaryLogClient binaryLogClient,
                MySqlConnection connection,
                Supplier<Boolean> makeBinlogFunction) {
            super(sourceConfig, binaryLogClient, connection);
            this.makeBinlogFunction = makeBinlogFunction;
        }

        @Override
        public EventDispatcher.SnapshotReceiver<MySqlPartition> getSnapshotReceiver() {
            EventDispatcher.SnapshotReceiver<MySqlPartition> snapshotReceiver =
                    super.getSnapshotReceiver();
            return new EventDispatcher.SnapshotReceiver<MySqlPartition>() {

                @Override
                public void changeRecord(
                        MySqlPartition partition,
                        DataCollectionSchema schema,
                        Envelope.Operation operation,
                        Object key,
                        Struct value,
                        OffsetContext offset,
                        ConnectHeaders headers)
                        throws InterruptedException {
                    snapshotReceiver.changeRecord(
                            partition, schema, operation, key, value, offset, headers);
                }

                @Override
                public void completeSnapshot() throws InterruptedException {
                    snapshotReceiver.completeSnapshot();
                    // make binlog events
                    makeBinlogFunction.get();
                }
            };
        }
    }
}