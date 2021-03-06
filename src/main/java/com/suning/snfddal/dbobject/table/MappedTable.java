/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package com.suning.snfddal.dbobject.table;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.suning.snfddal.api.ErrorCode;
import com.suning.snfddal.command.Prepared;
import com.suning.snfddal.dbobject.index.Index;
import com.suning.snfddal.dbobject.index.IndexType;
import com.suning.snfddal.dbobject.index.MappedIndex;
import com.suning.snfddal.dbobject.schema.Schema;
import com.suning.snfddal.engine.Database;
import com.suning.snfddal.engine.Mode;
import com.suning.snfddal.engine.Session;
import com.suning.snfddal.message.DbException;
import com.suning.snfddal.result.Row;
import com.suning.snfddal.result.RowList;
import com.suning.snfddal.route.rule.RuleColumn;
import com.suning.snfddal.route.rule.TableRouter;
import com.suning.snfddal.util.JdbcUtils;
import com.suning.snfddal.util.MathUtils;
import com.suning.snfddal.util.New;
import com.suning.snfddal.util.StatementBuilder;
import com.suning.snfddal.util.StringUtils;
import com.suning.snfddal.value.DataType;
import com.suning.snfddal.value.Value;
import com.suning.snfddal.value.ValueDate;
import com.suning.snfddal.value.ValueTime;
import com.suning.snfddal.value.ValueTimestamp;

/**
 * @author <a href="mailto:jorgie.mail@gmail.com">jorgie li</a>
 */
public class MappedTable extends Table {

    private static final int MAX_RETRY = 2;

    private static final long ROW_COUNT_APPROXIMATION = 100000;

    private final String originalSchema;
    private String metadataNode, originalTable, qualifiedTableName;
    private HashMap<String, PreparedStatement> preparedMap = New.hashMap();
    private final ArrayList<Index> indexes = New.arrayList();
    private final boolean emitUpdates;
    private MappedIndex linkedIndex;
    private boolean storesLowerCase;
    private boolean storesMixedCase;
    private boolean storesMixedCaseQuoted;
    private boolean supportsMixedCaseIdentifiers;
    private boolean globalTemporary;
    private boolean readOnly;
    private TableRouter tableRouter;

    public MappedTable(Schema schema, int id, String name, String metadataNode, String originalSchema,
            String originalTable, boolean emitUpdates, boolean force) {
        super(schema, id, name, false, true);
        this.metadataNode = metadataNode;
        this.originalSchema = originalSchema;
        this.originalTable = originalTable;
        this.emitUpdates = emitUpdates;
        try {
            connect();
        } catch (DbException e) {
            if (!force) {
                throw e;
            }
            Column[] cols = {};
            setColumns(cols);
            linkedIndex = new MappedIndex(this, id, IndexColumn.wrap(cols), IndexType.createNonUnique(false));
            indexes.add(linkedIndex);
        }
    }

    private void connect() {
        for (int retry = 0;; retry++) {
            try {
                Connection conn = null;
                try {
                    conn = database.getDataNode(metadataNode).getConnection();
                    readMetaData(conn);
                    return;
                } catch (Exception e) {
                    throw DbException.convert(e);
                } finally {
                    JdbcUtils.closeSilently(conn);
                }
            } catch (DbException e) {
                if (retry >= MAX_RETRY) {
                    throw e;
                }
            }
        }
    }

    private void readMetaData(Connection conn) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        storesLowerCase = meta.storesLowerCaseIdentifiers();
        storesMixedCase = meta.storesMixedCaseIdentifiers();
        storesMixedCaseQuoted = meta.storesMixedCaseQuotedIdentifiers();
        supportsMixedCaseIdentifiers = meta.supportsMixedCaseIdentifiers();
        ResultSet rs = meta.getTables(null, originalSchema, originalTable, null);
        if (rs.next() && rs.next()) {
            throw DbException.get(ErrorCode.SCHEMA_NAME_MUST_MATCH, originalTable);
        }
        rs.close();
        rs = meta.getColumns(null, originalSchema, originalTable, null);
        int i = 0;
        ArrayList<Column> columnList = New.arrayList();
        HashMap<String, Column> columnMap = New.hashMap();
        String catalog = null, schema = null;
        while (rs.next()) {
            String thisCatalog = rs.getString("TABLE_CAT");
            if (catalog == null) {
                catalog = thisCatalog;
            }
            String thisSchema = rs.getString("TABLE_SCHEM");
            if (schema == null) {
                schema = thisSchema;
            }
            if (!StringUtils.equals(catalog, thisCatalog) || !StringUtils.equals(schema, thisSchema)) {
                // if the table exists in multiple schemas or tables,
                // use the alternative solution
                columnMap.clear();
                columnList.clear();
                break;
            }
            String n = rs.getString("COLUMN_NAME");
            n = convertColumnName(n);
            int sqlType = rs.getInt("DATA_TYPE");
            long precision = rs.getInt("COLUMN_SIZE");
            precision = convertPrecision(sqlType, precision);
            int scale = rs.getInt("DECIMAL_DIGITS");
            scale = convertScale(sqlType, scale);
            int displaySize = MathUtils.convertLongToInt(precision);
            int type = DataType.convertSQLTypeToValueType(sqlType);
            Column col = new Column(n, type, precision, scale, displaySize);
            col.setTable(this, i++);
            columnList.add(col);
            columnMap.put(n, col);
        }
        rs.close();
        if (originalTable.indexOf('.') < 0 && !StringUtils.isNullOrEmpty(schema)) {
            qualifiedTableName = schema + "." + originalTable;
        } else {
            qualifiedTableName = originalTable;
        }
        // check if the table is accessible
        Statement stat = null;
        try {
            stat = conn.createStatement();
            rs = stat.executeQuery("SELECT * FROM " + qualifiedTableName + " T WHERE 1=0");
            if (columnList.size() == 0) {
                // alternative solution
                ResultSetMetaData rsMeta = rs.getMetaData();
                for (i = 0; i < rsMeta.getColumnCount();) {
                    String n = rsMeta.getColumnName(i + 1);
                    n = convertColumnName(n);
                    int sqlType = rsMeta.getColumnType(i + 1);
                    long precision = rsMeta.getPrecision(i + 1);
                    precision = convertPrecision(sqlType, precision);
                    int scale = rsMeta.getScale(i + 1);
                    scale = convertScale(sqlType, scale);
                    int displaySize = rsMeta.getColumnDisplaySize(i + 1);
                    int type = DataType.getValueTypeFromResultSet(rsMeta, i + 1);
                    Column col = new Column(n, type, precision, scale, displaySize);
                    col.setTable(this, i++);
                    columnList.add(col);
                    columnMap.put(n, col);
                }
            }
            rs.close();
        } catch (Exception e) {
            throw DbException.get(ErrorCode.TABLE_OR_VIEW_NOT_FOUND_1, e, originalTable + "(" + e.toString() + ")");
        } finally {
            JdbcUtils.closeSilently(stat);
        }
        Column[] cols = new Column[columnList.size()];
        columnList.toArray(cols);
        setColumns(cols);
        int id = getId();
        linkedIndex = new MappedIndex(this, id, IndexColumn.wrap(cols), IndexType.createNonUnique(false));
        indexes.add(linkedIndex);
        /*
        try {
            rs = meta.getPrimaryKeys(null, originalSchema, originalTable);
        } catch (Exception e) {
            // Some ODBC bridge drivers don't support it:
            // some combinations of "DataDirect SequeLink(R) for JDBC"
            // http://www.datadirect.com/index.ssp
            rs = null;
        }
        String pkName = "";
        ArrayList<Column> list;
        if (rs != null && rs.next()) {
            // the problem is, the rows are not sorted by KEY_SEQ
            list = New.arrayList();
            do {
                int idx = rs.getInt("KEY_SEQ");
                if (pkName == null) {
                    pkName = rs.getString("PK_NAME");
                }
                while (list.size() < idx) {
                    list.add(null);
                }
                String col = rs.getString("COLUMN_NAME");
                col = convertColumnName(col);
                Column column = columnMap.get(col);
                if (idx == 0) {
                    // workaround for a bug in the SQLite JDBC driver
                    list.add(column);
                } else {
                    list.set(idx - 1, column);
                }
            } while (rs.next());
            addIndex(list, IndexType.createPrimaryKey(false, false));
            rs.close();
        }
        try {
            rs = meta.getIndexInfo(null, originalSchema, originalTable, false, true);
        } catch (Exception e) {
            // Oracle throws an exception if the table is not found or is a
            // SYNONYM
            rs = null;
        }
        String indexName = null;
        list = New.arrayList();
        IndexType indexType = null;
        if (rs != null) {
            while (rs.next()) {
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    // ignore index statistics
                    continue;
                }
                String newIndex = rs.getString("INDEX_NAME");
                if (pkName.equals(newIndex)) {
                    continue;
                }
                if (indexName != null && !indexName.equals(newIndex)) {
                    addIndex(list, indexType);
                    indexName = null;
                }
                if (indexName == null) {
                    indexName = newIndex;
                    list.clear();
                }
                boolean unique = !rs.getBoolean("NON_UNIQUE");
                indexType = unique ? IndexType.createUnique(false, false) : IndexType.createNonUnique(false);
                String col = rs.getString("COLUMN_NAME");
                col = convertColumnName(col);
                Column column = columnMap.get(col);
                list.add(column);
            }
            rs.close();
        }
        if (indexName != null) {
            addIndex(list, indexType);
        }*/
        checkRuleColumn();
    }

    private static long convertPrecision(int sqlType, long precision) {
        // workaround for an Oracle problem:
        // for DATE columns, the reported precision is 7
        // for DECIMAL columns, the reported precision is 0
        switch (sqlType) {
        case Types.DECIMAL:
        case Types.NUMERIC:
            if (precision == 0) {
                precision = 65535;
            }
            break;
        case Types.DATE:
            precision = Math.max(ValueDate.PRECISION, precision);
            break;
        case Types.TIMESTAMP:
            precision = Math.max(ValueTimestamp.PRECISION, precision);
            break;
        case Types.TIME:
            precision = Math.max(ValueTime.PRECISION, precision);
            break;
        }
        return precision;
    }

    private static int convertScale(int sqlType, int scale) {
        // workaround for an Oracle problem:
        // for DECIMAL columns, the reported precision is -127
        switch (sqlType) {
        case Types.DECIMAL:
        case Types.NUMERIC:
            if (scale < 0) {
                scale = 32767;
            }
            break;
        }
        return scale;
    }

    private String convertColumnName(String columnName) {
        if ((storesMixedCase || storesLowerCase) && columnName.equals(StringUtils.toLowerEnglish(columnName))) {
            columnName = StringUtils.toUpperEnglish(columnName);
        } else if (storesMixedCase && !supportsMixedCaseIdentifiers) {
            // TeraData
            columnName = StringUtils.toUpperEnglish(columnName);
        } else if (storesMixedCase && storesMixedCaseQuoted) {
            // MS SQL Server (identifiers are case insensitive even if quoted)
            columnName = StringUtils.toUpperEnglish(columnName);
        }
        return columnName;
    }

    /*
    private void addIndex(ArrayList<Column> list, IndexType indexType) {
        Column[] cols = new Column[list.size()];
        list.toArray(cols);
        Index index = new MappedIndex(this, 0, IndexColumn.wrap(cols), indexType);
        indexes.add(index);
    }*/
    
    private void checkRuleColumn() {
        TableRouter tableRouter = getTableRouter();
        if(tableRouter != null) {
            for (RuleColumn ruleCol : tableRouter.getRuleColumns()) {
                Column[] columns = getColumns();
                Column matched = null;
                for (Column column : columns) {
                    String colName = column.getName();
                    if(colName.equalsIgnoreCase(ruleCol.getName())) {
                        matched = column;
                        break;
                    }                
                }
                if(matched == null){
                    throw DbException.getInvalidValueException("RuleColumn", ruleCol);
                }
            }
        }
    }

    @Override
    public String getDropSQL() {
        return "DROP TABLE IF EXISTS " + getSQL();
    }

    @Override
    public String getCreateSQL() {

        Database db = getDatabase();
        if (db == null) {
            // closed
            return null;
        }
        StatementBuilder buff = new StatementBuilder("CREATE ");
        if (isTemporary()) {
            if (globalTemporary) {
                buff.append("GLOBAL ");
            } else {
                buff.append("LOCAL ");
            }
            buff.append("TEMPORARY ");
        } else if (isPersistIndexes()) {
            buff.append("CACHED ");
        } else {
            buff.append("MEMORY ");
        }
        buff.append("TABLE ");
        if (isHidden) {
            buff.append("IF NOT EXISTS ");
        }
        buff.append(getSQL());
        if (comment != null) {
            buff.append(" COMMENT ").append(StringUtils.quoteStringSQL(comment));
        }
        buff.append("(\n    ");
        for (Column column : columns) {
            buff.appendExceptFirst(",\n    ");
            buff.append(column.getCreateSQL());
        }
        buff.append("\n)");
        if (!isPersistIndexes() && !isPersistData()) {
            buff.append("\nNOT PERSISTENT");
        }
        if (isHidden) {
            buff.append("\nHIDDEN");
        }
        return buff.toString();
    }

    @Override
    public Index addIndex(Session session, String indexName, int indexId, IndexColumn[] cols, IndexType indexType,
            boolean create, String indexComment) {
        throw DbException.getUnsupportedException("LINK");
    }

    @Override
    public boolean lock(Session session, boolean exclusive, boolean forceLockEvenInMvcc) {
        // nothing to do
        return false;
    }

    @Override
    public boolean isLockedExclusively() {
        return false;
    }

    @Override
    public Index getScanIndex(Session session) {
        return linkedIndex;
    }

    private void checkReadOnly() {
        if (readOnly) {
            throw DbException.get(ErrorCode.DATABASE_IS_READ_ONLY);
        }
    }

    @Override
    public void removeRow(Session session, Row row) {
        checkReadOnly();
        getScanIndex(session).remove(session, row);
    }

    @Override
    public void addRow(Session session, Row row) {
        checkReadOnly();
        getScanIndex(session).add(session, row);
    }

    @Override
    public void close(Session session) {
        // do nothing
    }

    @Override
    public synchronized long getRowCount(Session session) {
        String sql = "SELECT COUNT(*) FROM " + qualifiedTableName;
        try {
            PreparedStatement prep = execute(session, sql, metadataNode, null, false);
            ResultSet rs = prep.getResultSet();
            rs.next();
            long count = rs.getLong(1);
            rs.close();
            reusePreparedStatement(prep, sql);
            return count;
        } catch (Exception e) {
            throw wrapException(sql, e);
        }
    }

    /**
     * Wrap a SQL exception that occurred while accessing a linked table.
     *
     * @param sql the SQL statement
     * @param ex the exception from the remote database
     * @return the wrapped exception
     */
    public static DbException wrapException(String sql, Exception ex) {
        SQLException e = DbException.toSQLException(ex);
        return DbException.get(ErrorCode.ERROR_ACCESSING_DATABASE_TABLE_2, e, sql, e.toString());
    }

    public String getQualifiedTable() {
        return qualifiedTableName;
    }
    
    public String getMetadataNode() {
        return metadataNode;
    }

    /**
     * Execute a SQL statement using the given parameters. Prepared statements
     * are kept in a hash map to avoid re-creating them.
     *
     * @param sql the SQL statement
     * @param params the parameters or null
     * @param reusePrepared if the prepared statement can be re-used immediately
     * @return the prepared statement, or null if it is re-used
     */
    public PreparedStatement execute(Session session, String shardName, String sql, List<Value> params, boolean reusePrepared) {

        for (int retry = 0;; retry++) {
            Connection conn = null;
            try {
                conn = session.getDataNodeConnection(shardName);
                PreparedStatement prep = preparedMap.remove(sql);
                if (prep == null) {
                    prep = conn.prepareStatement(sql);
                }
                if (trace.isDebugEnabled()) {
                    StatementBuilder buff = new StatementBuilder();
                    buff.append(getName()).append(":\n").append(sql);
                    if (params != null && params.size() > 0) {
                        buff.append(" {");
                        int i = 1;
                        for (Value v : params) {
                            buff.appendExceptFirst(", ");
                            buff.append(i++).append(": ").append(v.getSQL());
                        }
                        buff.append('}');
                    }
                    buff.append(';');
                    trace.debug(buff.toString());
                }
                if (params != null) {
                    for (int i = 0, size = params.size(); i < size; i++) {
                        Value v = params.get(i);
                        v.set(prep, i + 1);
                    }
                }
                prep.execute();
                if (reusePrepared) {
                    reusePreparedStatement(prep, sql);
                    return null;
                }
                return prep;

            } catch (SQLException e) {
                if (retry >= MAX_RETRY) {
                    throw DbException.convert(e);
                }
                connect();
            } finally {
                // JdbcUtils.closeSilently(conn)
            }
        }
    }

    @Override
    public void unlock(Session s) {
        // nothing to do
    }

    @Override
    public void checkRename() {
        // ok
    }

    @Override
    public void checkSupportAlter() {
        throw DbException.getUnsupportedException(getName());
    }

    @Override
    public void truncate(Session session) {
        throw DbException.getUnsupportedException(getName());
    }

    @Override
    public boolean canGetRowCount() {
        return true;
    }

    @Override
    public boolean canDrop() {
        return true;
    }

    @Override
    public String getTableType() {
        return Table.TABLE;
    }

    @Override
    public void removeChildrenAndResources(Session session) {
        super.removeChildrenAndResources(session);
        close(session);
        preparedMap = null;
        invalidate();
    }

    public boolean isOracle() {
        return Mode.ORACLE.equals(database.getMode().getName());

    }

    @Override
    public ArrayList<Index> getIndexes() {
        return indexes;
    }

    @Override
    public Index getUniqueIndex() {
        for (Index idx : indexes) {
            if (idx.getIndexType().isUnique()) {
                return idx;
            }
        }
        return null;
    }

    @Override
    public void updateRows(Prepared prepared, Session session, RowList rows) {
        boolean deleteInsert;
        checkReadOnly();
        if (emitUpdates) {
            for (rows.reset(); rows.hasNext();) {
                prepared.checkCanceled();
                Row oldRow = rows.next();
                Row newRow = rows.next();
                linkedIndex.update(session, oldRow, newRow);
            }
            deleteInsert = false;
        } else {
            deleteInsert = true;
        }
        if (deleteInsert) {
            super.updateRows(prepared, session, rows);
        }
    }

    public void setGlobalTemporary(boolean globalTemporary) {
        this.globalTemporary = globalTemporary;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    @Override
    public long getRowCountApproximation() {
        return ROW_COUNT_APPROXIMATION;
    }

    @Override
    public long getDiskSpaceUsed() {
        return 0;
    }

    /**
     * Add this prepared statement to the list of cached statements.
     *
     * @param prep the prepared statement
     * @param sql the SQL statement
     */
    public void reusePreparedStatement(PreparedStatement prep, String sql) {
        preparedMap.put(sql, prep);
    }

    @Override
    public boolean isDeterministic() {
        return false;
    }

    /**
     * Convert the values if required. Default values are not set (kept as
     * null).
     *
     * @param session the session
     * @param row the row
     */
    @Override
    public void validateConvertUpdateSequence(Session session, Row row) {
        for (int i = 0; i < columns.length; i++) {
            Value value = row.getValue(i);
            if (value != null) {
                // null means use the default value
                Column column = columns[i];
                Value v2 = column.validateConvertUpdateSequence(session, value);
                if (v2 != value) {
                    row.setValue(i, v2);
                }
            }
        }
    }

    /**
     * Get or generate a default value for the given column. Default values are
     * not set (kept as null).
     *
     * @param session the session
     * @param column the column
     * @return the value
     */
    @Override
    public Value getDefaultValue(Session session, Column column) {
        return null;
    }

    /**
     * @return the tableRouter
     */
    public TableRouter getTableRouter() {
        return tableRouter;
    }

    /**
     * @param tableRouter the tableRouter to set
     */
    public void setTableRouter(TableRouter tableRouter) {
        this.tableRouter = tableRouter;
    }

}
