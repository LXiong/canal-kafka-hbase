package com.zgl.hadoop.configuration.kafka.canal;

import java.util.*;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.google.protobuf.ByteString;
import java.io.Serializable;

/**
 * \* Created with IntelliJ IDEA.
 * \* User: zgl
 * \* Date: 2018-10-10
 * \* Time: 14:40
 * \* To change this template use File | Settings | File Templates.
 * \* Description:
 * \
 */
public class FlatMessage implements Serializable {
    private static final long         serialVersionUID = -3386650678735860050L;

    private long                      id;
    private String                    database;
    private String                    table;
    private Boolean                   isDdl;
    private String                    type;
    private Long                      ts;
    private String                    sql;
    private Map<String, Integer>      sqlType;
    private Map<String, String>       mysqlType;
    private List<Map<String, String>> data;
    private List<Map<String, String>> old;

    public FlatMessage(){
    }

    public FlatMessage(long id){
        this.id = id;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public Boolean getIsDdl() {
        return isDdl;
    }

    public void setIsDdl(Boolean isDdl) {
        this.isDdl = isDdl;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getTs() {
        return ts;
    }

    public void setTs(Long ts) {
        this.ts = ts;
    }

    public String getSql() {
        return sql;
    }

    public void setSql(String sql) {
        this.sql = sql;
    }

    public Map<String, Integer> getSqlType() {
        return sqlType;
    }

    public void setSqlType(Map<String, Integer> sqlType) {
        this.sqlType = sqlType;
    }

    public Map<String, String> getMysqlType() {
        return mysqlType;
    }

    public void setMysqlType(Map<String, String> mysqlType) {
        this.mysqlType = mysqlType;
    }

    public List<Map<String, String>> getData() {
        return data;
    }

    public void setData(List<Map<String, String>> data) {
        this.data = data;
    }

    public List<Map<String, String>> getOld() {
        return old;
    }

    public void setOld(List<Map<String, String>> old) {
        this.old = old;
    }

    /**
     * 将Message转换为FlatMessage
     *
     * @param message 原生message
     * @return FlatMessage列表
     */
    public static List<FlatMessage> messageConverter(Message message) {
        try {
            if (message == null) {
                return null;
            }

            List<FlatMessage> flatMessages = new ArrayList<>();

            List<ByteString> rawEntries = message.getRawEntries();

            for (ByteString byteString : rawEntries) {
                CanalEntry.Entry entry = CanalEntry.Entry.parseFrom(byteString);
                if (entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONBEGIN
                        || entry.getEntryType() == CanalEntry.EntryType.TRANSACTIONEND) {
                    continue;
                }

                CanalEntry.RowChange rowChange;
                try {
                    rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
                } catch (Exception e) {
                    throw new RuntimeException(
                            "ERROR ## parser of eromanga-event has an error , data:" + entry.toString(),
                            e);
                }

                CanalEntry.EventType eventType = rowChange.getEventType();

                FlatMessage flatMessage = new FlatMessage(message.getId());
                flatMessages.add(flatMessage);
                flatMessage.setDatabase(entry.getHeader().getSchemaName());
                flatMessage.setTable(entry.getHeader().getTableName());
                flatMessage.setIsDdl(rowChange.getIsDdl());
                flatMessage.setType(eventType.toString());
                flatMessage.setTs(System.currentTimeMillis());
                flatMessage.setSql(rowChange.getSql());

                if (!rowChange.getIsDdl()) {
                    Map<String, Integer> sqlType = new LinkedHashMap<>();
                    Map<String, String> mysqlType = new LinkedHashMap<>();
                    List<Map<String, String>> data = new ArrayList<>();
                    List<Map<String, String>> old = new ArrayList<>();

                    Set<String> updateSet = new HashSet<>();
                    for (CanalEntry.RowData rowData : rowChange.getRowDatasList()) {
                        if (eventType != CanalEntry.EventType.INSERT && eventType != CanalEntry.EventType.UPDATE
                                && eventType != CanalEntry.EventType.DELETE) {
                            continue;
                        }

                        Map<String, String> row = new LinkedHashMap<>();
                        List<CanalEntry.Column> columns;

                        if (eventType == CanalEntry.EventType.DELETE) {
                            columns = rowData.getBeforeColumnsList();
                        } else {
                            columns = rowData.getAfterColumnsList();
                        }

                        for (CanalEntry.Column column : columns) {
                            sqlType.put(column.getName(), column.getSqlType());
                            mysqlType.put(column.getName(), column.getMysqlType());
                            row.put(column.getName(), column.getValue());
                            // 获取update为true的字段
                            if (column.getUpdated()) {
                                updateSet.add(column.getName());
                            }
                        }
                        if (!row.isEmpty()) {
                            data.add(row);
                        }

                        if (eventType == CanalEntry.EventType.UPDATE) {
                            Map<String, String> rowOld = new LinkedHashMap<>();
                            for (CanalEntry.Column column : rowData.getBeforeColumnsList()) {
                                if (updateSet.contains(column.getName())) {
                                    rowOld.put(column.getName(), column.getValue());
                                }
                            }
                            // update操作将记录修改前的值
                            if (!rowOld.isEmpty()) {
                                old.add(rowOld);
                            }
                        }
                    }
                    if (!sqlType.isEmpty()) {
                        flatMessage.setSqlType(sqlType);
                    }
                    if (!mysqlType.isEmpty()) {
                        flatMessage.setMysqlType(mysqlType);
                    }
                    if (!data.isEmpty()) {
                        flatMessage.setData(data);
                    }
                    if (!old.isEmpty()) {
                        flatMessage.setOld(old);
                    }
                }
            }
            return flatMessages;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 将FlatMessage按指定的字段值hash拆分
     *
     * @param flatMessage flatMessage
     * @param partitionsNum 分区数量
     * @param pkHashConfig hash映射
     * @return 拆分后的flatMessage数组
     */
    public static FlatMessage[] messagePartition(FlatMessage flatMessage, Integer partitionsNum,
                                                 Map<String, String> pkHashConfig) {
        if (partitionsNum == null) {
            partitionsNum = 1;
        }
        FlatMessage[] partitionMessages = new FlatMessage[partitionsNum];

        String pk = pkHashConfig.get(flatMessage.getDatabase() + "." + flatMessage.getTable());
        if (pk == null || flatMessage.getIsDdl()) {
            partitionMessages[0] = flatMessage;
        } else {
            if (flatMessage.getData() != null) {
                int idx = 0;
                for (Map<String, String> row : flatMessage.getData()) {
                    String value = row.get(pk);
                    if (value == null) {
                        value = "";
                    }
                    int hash = value.hashCode();
                    int pkHash = Math.abs(hash) % partitionsNum;
                    // math.abs可能返回负值，这里再取反，把出现负值的数据还是写到固定的分区，仍然可以保证消费顺序
                    pkHash = Math.abs(pkHash);

                    FlatMessage flatMessageTmp = partitionMessages[pkHash];
                    if (flatMessageTmp == null) {
                        flatMessageTmp = new FlatMessage(flatMessage.getId());
                        partitionMessages[pkHash] = flatMessageTmp;
                        flatMessageTmp.setDatabase(flatMessage.getDatabase());
                        flatMessageTmp.setTable(flatMessage.getTable());
                        flatMessageTmp.setIsDdl(flatMessage.getIsDdl());
                        flatMessageTmp.setType(flatMessage.getType());
                        flatMessageTmp.setSql(flatMessage.getSql());
                        flatMessageTmp.setSqlType(flatMessage.getSqlType());
                        flatMessageTmp.setMysqlType(flatMessage.getMysqlType());
                    }
                    List<Map<String, String>> data = flatMessageTmp.getData();
                    if (data == null) {
                        data = new ArrayList<>();
                        flatMessageTmp.setData(data);
                    }
                    data.add(row);
                    if (flatMessage.getOld() != null && !flatMessage.getOld().isEmpty()) {
                        List<Map<String, String>> old = flatMessageTmp.getOld();
                        if (old == null) {
                            old = new ArrayList<>();
                            flatMessageTmp.setOld(old);
                        }
                        old.add(flatMessage.getOld().get(idx));
                    }
                    idx++;
                }
            }
        }
        return partitionMessages;
    }

    @Override
    public String toString() {
        return "FlatMessage{" + "id=" + id + ", database='" + database + '\'' + ", table='" + table + '\'' + ", isDdl="
                + isDdl + ", type='" + type + '\'' + ", ts=" + ts + ", sql='" + sql + '\'' + ", sqlType=" + sqlType
                + ", mysqlType=" + mysqlType + ", data=" + data + ", old=" + old + '}';
    }
}