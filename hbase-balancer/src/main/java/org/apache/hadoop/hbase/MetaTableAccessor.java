/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell.Type;
import org.apache.hadoop.hbase.ClientMetaTableAccessor.QueryType;
import org.apache.hadoop.hbase.client.AsyncTable;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Consistency;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionInfoBuilder;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.client.RegionReplicaUtil;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableState;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter;
import org.apache.hadoop.hbase.filter.RowFilter;
import org.apache.hadoop.hbase.filter.SubstringComparator;
import org.apache.hadoop.hbase.master.RegionState;
import org.apache.hadoop.hbase.master.RegionState.State;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.ExceptionUtil;
import org.apache.hadoop.hbase.util.FutureUtils;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.PairOfSameType;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.annotations.VisibleForTesting;

import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ClientProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MultiRowMutationProtos.MultiRowMutationService;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MultiRowMutationProtos.MutateRowsRequest;
import org.apache.hadoop.hbase.shaded.protobuf.generated.MultiRowMutationProtos.MutateRowsResponse;

/**
 * Read/write operations on <code>hbase:meta</code> region as well as assignment information stored
 * to <code>hbase:meta</code>.
 * <p/>
 * Some of the methods of this class take ZooKeeperWatcher as a param. The only reason for this is
 * when this class is used on client-side (e.g. HBaseAdmin), we want to use short-lived connection
 * (opened before each operation, closed right after), while when used on HM or HRS (like in
 * AssignmentManager) we want permanent connection.
 * <p/>
 * HBASE-10070 adds a replicaId to HRI, meaning more than one HRI can be defined for the same table
 * range (table, startKey, endKey). For every range, there will be at least one HRI defined which is
 * called default replica.
 * <p/>
 * <h2>Meta layout</h2> For each table there is single row named for the table with a 'table' column
 * family. The column family currently has one column in it, the 'state' column:
 *
 * <pre>
 * table:state => contains table state
 * </pre>
 *
 * For the catalog family, see the comments of {@link CatalogFamilyFormat} for more details.
 * <p/>
 * TODO: Add rep_barrier for serial replication explanation. See SerialReplicationChecker.
 * <p/>
 * The actual layout of meta should be encapsulated inside MetaTableAccessor methods, and should not
 * leak out of it (through Result objects, etc)
 * @see CatalogFamilyFormat
 * @see ClientMetaTableAccessor
 */
@InterfaceAudience.Private
public final class MetaTableAccessor {

  private static final Logger LOG = LoggerFactory.getLogger(MetaTableAccessor.class);
  private static final Logger METALOG = LoggerFactory.getLogger("org.apache.hadoop.hbase.META");

  private MetaTableAccessor() {
  }

  @VisibleForTesting
  public static final byte[] REPLICATION_PARENT_QUALIFIER = Bytes.toBytes("parent");

  private static final byte ESCAPE_BYTE = (byte) 0xFF;

  private static final byte SEPARATED_BYTE = 0x00;

  ////////////////////////
  // Reading operations //
  ////////////////////////

  /**
   * Performs a full scan of <code>hbase:meta</code> for regions.
   * @param connection connection we're using
   * @param visitor Visitor invoked against each row in regions family.
   */
  public static void fullScanRegions(Connection connection,
    final ClientMetaTableAccessor.Visitor visitor) throws IOException {
    scanMeta(connection, null, null, QueryType.REGION, visitor);
  }

  /**
   * Performs a full scan of <code>hbase:meta</code> for regions.
   * @param connection connection we're using
   */
  public static List<Result> fullScanRegions(Connection connection) throws IOException {
    return fullScan(connection, QueryType.REGION);
  }

  /**
   * Performs a full scan of <code>hbase:meta</code> for tables.
   * @param connection connection we're using
   * @param visitor Visitor invoked against each row in tables family.
   */
  public static void fullScanTables(Connection connection,
    final ClientMetaTableAccessor.Visitor visitor) throws IOException {
    scanMeta(connection, null, null, QueryType.TABLE, visitor);
  }

  /**
   * Performs a full scan of <code>hbase:meta</code>.
   * @param connection connection we're using
   * @param type scanned part of meta
   * @return List of {@link Result}
   */
  private static List<Result> fullScan(Connection connection, QueryType type) throws IOException {
    ClientMetaTableAccessor.CollectAllVisitor v = new ClientMetaTableAccessor.CollectAllVisitor();
    scanMeta(connection, null, null, type, v);
    return v.getResults();
  }

  /**
   * Callers should call close on the returned {@link Table} instance.
   * @param connection connection we're using to access Meta
   * @return An {@link Table} for <code>hbase:meta</code>
   * @throws NullPointerException if {@code connection} is {@code null}
   */
  public static Table getMetaHTable(final Connection connection) throws IOException {
    // We used to pass whole CatalogTracker in here, now we just pass in Connection
    Objects.requireNonNull(connection, "Connection cannot be null");
    if (connection.isClosed()) {
      throw new IOException("connection is closed");
    }
    return connection.getTable(TableName.META_TABLE_NAME);
  }

  /**
   * Gets the region info and assignment for the specified region.
   * @param connection connection we're using
   * @param regionName Region to lookup.
   * @return Location and RegionInfo for <code>regionName</code>
   * @deprecated use {@link #getRegionLocation(Connection, byte[])} instead
   */
  @Deprecated
  public static Pair<RegionInfo, ServerName> getRegion(Connection connection, byte[] regionName)
    throws IOException {
    HRegionLocation location = getRegionLocation(connection, regionName);
    return location == null ? null : new Pair<>(location.getRegion(), location.getServerName());
  }

  /**
   * Returns the HRegionLocation from meta for the given region
   * @param connection connection we're using
   * @param regionName region we're looking for
   * @return HRegionLocation for the given region
   */
  public static HRegionLocation getRegionLocation(Connection connection, byte[] regionName)
    throws IOException {
    byte[] row = regionName;
    RegionInfo parsedInfo = null;
    try {
      parsedInfo = CatalogFamilyFormat.parseRegionInfoFromRegionName(regionName);
      row = CatalogFamilyFormat.getMetaKeyForRegion(parsedInfo);
    } catch (Exception parseEx) {
      // Ignore. This is used with tableName passed as regionName.
    }
    Get get = new Get(row);
    get.addFamily(HConstants.CATALOG_FAMILY);
    Result r;
    try (Table t = getMetaHTable(connection)) {
      r = t.get(get);
    }
    RegionLocations locations = CatalogFamilyFormat.getRegionLocations(r);
    return locations == null ? null :
      locations.getRegionLocation(parsedInfo == null ? 0 : parsedInfo.getReplicaId());
  }

  /**
   * Returns the HRegionLocation from meta for the given region
   * @param connection connection we're using
   * @param regionInfo region information
   * @return HRegionLocation for the given region
   */
  public static HRegionLocation getRegionLocation(Connection connection, RegionInfo regionInfo)
    throws IOException {
    return CatalogFamilyFormat.getRegionLocation(getCatalogFamilyRow(connection, regionInfo),
      regionInfo, regionInfo.getReplicaId());
  }

  /**
   * @return Return the {@link HConstants#CATALOG_FAMILY} row from hbase:meta.
   */
  public static Result getCatalogFamilyRow(Connection connection, RegionInfo ri)
    throws IOException {
    Get get = new Get(CatalogFamilyFormat.getMetaKeyForRegion(ri));
    get.addFamily(HConstants.CATALOG_FAMILY);
    try (Table t = getMetaHTable(connection)) {
      return t.get(get);
    }
  }

  /**
   * Gets the result in hbase:meta for the specified region.
   * @param connection connection we're using
   * @param regionName region we're looking for
   * @return result of the specified region
   */
  public static Result getRegionResult(Connection connection, byte[] regionName)
    throws IOException {
    Get get = new Get(regionName);
    get.addFamily(HConstants.CATALOG_FAMILY);
    try (Table t = getMetaHTable(connection)) {
      return t.get(get);
    }
  }

  /**
   * Scans META table for a row whose key contains the specified <B>regionEncodedName</B>, returning
   * a single related <code>Result</code> instance if any row is found, null otherwise.
   * @param connection the connection to query META table.
   * @param regionEncodedName the region encoded name to look for at META.
   * @return <code>Result</code> instance with the row related info in META, null otherwise.
   * @throws IOException if any errors occur while querying META.
   */
  public static Result scanByRegionEncodedName(Connection connection, String regionEncodedName)
    throws IOException {
    RowFilter rowFilter =
      new RowFilter(CompareOperator.EQUAL, new SubstringComparator(regionEncodedName));
    Scan scan = getMetaScan(connection, 1);
    scan.setFilter(rowFilter);
    ResultScanner resultScanner = getMetaHTable(connection).getScanner(scan);
    return resultScanner.next();
  }

  /**
   * @return Return all regioninfos listed in the 'info:merge*' columns of the
   *         <code>regionName</code> row.
   */
  @Nullable
  public static List<RegionInfo> getMergeRegions(Connection connection, byte[] regionName)
    throws IOException {
    return getMergeRegions(getRegionResult(connection, regionName).rawCells());
  }

  /**
   * Check whether the given {@code regionName} has any 'info:merge*' columns.
   */
  public static boolean hasMergeRegions(Connection conn, byte[] regionName) throws IOException {
    return hasMergeRegions(getRegionResult(conn, regionName).rawCells());
  }

  /**
   * @return Deserialized values of &lt;qualifier,regioninfo&gt; pairs taken from column values that
   *         match the regex 'info:merge.*' in array of <code>cells</code>.
   */
  @Nullable
  public static Map<String, RegionInfo> getMergeRegionsWithName(Cell[] cells) {
    if (cells == null) {
      return null;
    }
    Map<String, RegionInfo> regionsToMerge = null;
    for (Cell cell : cells) {
      if (!isMergeQualifierPrefix(cell)) {
        continue;
      }
      // Ok. This cell is that of a info:merge* column.
      RegionInfo ri = RegionInfo.parseFromOrNull(cell.getValueArray(), cell.getValueOffset(),
        cell.getValueLength());
      if (ri != null) {
        if (regionsToMerge == null) {
          regionsToMerge = new LinkedHashMap<>();
        }
        regionsToMerge.put(Bytes.toString(CellUtil.cloneQualifier(cell)), ri);
      }
    }
    return regionsToMerge;
  }

  /**
   * @return Deserialized regioninfo values taken from column values that match the regex
   *         'info:merge.*' in array of <code>cells</code>.
   */
  @Nullable
  public static List<RegionInfo> getMergeRegions(Cell[] cells) {
    Map<String, RegionInfo> mergeRegionsWithName = getMergeRegionsWithName(cells);
    return (mergeRegionsWithName == null) ? null : new ArrayList<>(mergeRegionsWithName.values());
  }

  /**
   * @return True if any merge regions present in <code>cells</code>; i.e. the column in
   *         <code>cell</code> matches the regex 'info:merge.*'.
   */
  public static boolean hasMergeRegions(Cell[] cells) {
    for (Cell cell : cells) {
      if (!isMergeQualifierPrefix(cell)) {
        continue;
      }
      return true;
    }
    return false;
  }

  /**
   * @return True if the column in <code>cell</code> matches the regex 'info:merge.*'.
   */
  private static boolean isMergeQualifierPrefix(Cell cell) {
    // Check to see if has family and that qualifier starts with the merge qualifier 'merge'
    return CellUtil.matchingFamily(cell, HConstants.CATALOG_FAMILY) &&
      PrivateCellUtil.qualifierStartsWith(cell, HConstants.MERGE_QUALIFIER_PREFIX);
  }

  /**
   * Checks if the specified table exists. Looks at the hbase:meta table hosted on the specified
   * server.
   * @param connection connection we're using
   * @param tableName table to check
   * @return true if the table exists in meta, false if not
   */
  public static boolean tableExists(Connection connection, final TableName tableName)
    throws IOException {
    // Catalog tables always exist.
    return tableName.equals(TableName.META_TABLE_NAME) ||
      getTableState(connection, tableName) != null;
  }

  /**
   * Lists all of the regions currently in META.
   * @param connection to connect with
   * @param excludeOfflinedSplitParents False if we are to include offlined/splitparents regions,
   *          true and we'll leave out offlined regions from returned list
   * @return List of all user-space regions.
   */
  @VisibleForTesting
  public static List<RegionInfo> getAllRegions(Connection connection,
    boolean excludeOfflinedSplitParents) throws IOException {
    List<Pair<RegionInfo, ServerName>> result;

    result = getTableRegionsAndLocations(connection, null, excludeOfflinedSplitParents);

    return getListOfRegionInfos(result);

  }

  /**
   * Gets all of the regions of the specified table. Do not use this method to get meta table
   * regions, use methods in MetaTableLocator instead.
   * @param connection connection we're using
   * @param tableName table we're looking for
   * @return Ordered list of {@link RegionInfo}.
   */
  public static List<RegionInfo> getTableRegions(Connection connection, TableName tableName)
    throws IOException {
    return getTableRegions(connection, tableName, false);
  }

  /**
   * Gets all of the regions of the specified table. Do not use this method to get meta table
   * regions, use methods in MetaTableLocator instead.
   * @param connection connection we're using
   * @param tableName table we're looking for
   * @param excludeOfflinedSplitParents If true, do not include offlined split parents in the
   *          return.
   * @return Ordered list of {@link RegionInfo}.
   */
  public static List<RegionInfo> getTableRegions(Connection connection, TableName tableName,
    final boolean excludeOfflinedSplitParents) throws IOException {
    List<Pair<RegionInfo, ServerName>> result =
      getTableRegionsAndLocations(connection, tableName, excludeOfflinedSplitParents);
    return getListOfRegionInfos(result);
  }

  private static List<RegionInfo>
    getListOfRegionInfos(final List<Pair<RegionInfo, ServerName>> pairs) {
    if (pairs == null || pairs.isEmpty()) {
      return Collections.emptyList();
    }
    List<RegionInfo> result = new ArrayList<>(pairs.size());
    for (Pair<RegionInfo, ServerName> pair : pairs) {
      result.add(pair.getFirst());
    }
    return result;
  }

  /**
   * This method creates a Scan object that will only scan catalog rows that belong to the specified
   * table. It doesn't specify any columns. This is a better alternative to just using a start row
   * and scan until it hits a new table since that requires parsing the HRI to get the table name.
   * @param tableName bytes of table's name
   * @return configured Scan object
   * @deprecated This is internal so please remove it when we get a chance.
   */
  @Deprecated
  public static Scan getScanForTableName(Connection connection, TableName tableName) {
    // Start key is just the table name with delimiters
    byte[] startKey = ClientMetaTableAccessor.getTableStartRowForMeta(tableName, QueryType.REGION);
    // Stop key appends the smallest possible char to the table name
    byte[] stopKey = ClientMetaTableAccessor.getTableStopRowForMeta(tableName, QueryType.REGION);

    Scan scan = getMetaScan(connection, -1);
    scan.withStartRow(startKey);
    scan.withStopRow(stopKey);
    return scan;
  }

  private static Scan getMetaScan(Connection connection, int rowUpperLimit) {
    Scan scan = new Scan();
    int scannerCaching = connection.getConfiguration().getInt(HConstants.HBASE_META_SCANNER_CACHING,
      HConstants.DEFAULT_HBASE_META_SCANNER_CACHING);
    if (connection.getConfiguration().getBoolean(HConstants.USE_META_REPLICAS,
      HConstants.DEFAULT_USE_META_REPLICAS)) {
      scan.setConsistency(Consistency.TIMELINE);
    }
    if (rowUpperLimit > 0) {
      scan.setLimit(rowUpperLimit);
      scan.setReadType(Scan.ReadType.PREAD);
    }
    scan.setCaching(scannerCaching);
    return scan;
  }

  /**
   * Do not use this method to get meta table regions, use methods in MetaTableLocator instead.
   * @param connection connection we're using
   * @param tableName table we're looking for
   * @return Return list of regioninfos and server.
   */
  public static List<Pair<RegionInfo, ServerName>>
    getTableRegionsAndLocations(Connection connection, TableName tableName) throws IOException {
    return getTableRegionsAndLocations(connection, tableName, true);
  }

  /**
   * Do not use this method to get meta table regions, use methods in MetaTableLocator instead.
   * @param connection connection we're using
   * @param tableName table to work with, can be null for getting all regions
   * @param excludeOfflinedSplitParents don't return split parents
   * @return Return list of regioninfos and server addresses.
   */
  // What happens here when 1M regions in hbase:meta? This won't scale?
  public static List<Pair<RegionInfo, ServerName>> getTableRegionsAndLocations(
    Connection connection, @Nullable final TableName tableName,
    final boolean excludeOfflinedSplitParents) throws IOException {
    if (tableName != null && tableName.equals(TableName.META_TABLE_NAME)) {
      throw new IOException(
        "This method can't be used to locate meta regions;" + " use MetaTableLocator instead");
    }
    // Make a version of CollectingVisitor that collects RegionInfo and ServerAddress
    ClientMetaTableAccessor.CollectRegionLocationsVisitor visitor =
      new ClientMetaTableAccessor.CollectRegionLocationsVisitor(excludeOfflinedSplitParents);
    scanMeta(connection,
      ClientMetaTableAccessor.getTableStartRowForMeta(tableName, QueryType.REGION),
      ClientMetaTableAccessor.getTableStopRowForMeta(tableName, QueryType.REGION), QueryType.REGION,
      visitor);
    return visitor.getResults();
  }

  public static void fullScanMetaAndPrint(Connection connection) throws IOException {
    ClientMetaTableAccessor.Visitor v = r -> {
      if (r == null || r.isEmpty()) {
        return true;
      }
      LOG.info("fullScanMetaAndPrint.Current Meta Row: " + r);
      TableState state = CatalogFamilyFormat.getTableState(r);
      if (state != null) {
        LOG.info("fullScanMetaAndPrint.Table State={}" + state);
      } else {
        RegionLocations locations = CatalogFamilyFormat.getRegionLocations(r);
        if (locations == null) {
          return true;
        }
        for (HRegionLocation loc : locations.getRegionLocations()) {
          if (loc != null) {
            LOG.info("fullScanMetaAndPrint.HRI Print={}", loc.getRegion());
          }
        }
      }
      return true;
    };
    scanMeta(connection, null, null, QueryType.ALL, v);
  }

  public static void scanMetaForTableRegions(Connection connection,
    ClientMetaTableAccessor.Visitor visitor, TableName tableName) throws IOException {
    scanMeta(connection, tableName, QueryType.REGION, Integer.MAX_VALUE, visitor);
  }

  private static void scanMeta(Connection connection, TableName table, QueryType type, int maxRows,
    final ClientMetaTableAccessor.Visitor visitor) throws IOException {
    scanMeta(connection, ClientMetaTableAccessor.getTableStartRowForMeta(table, type),
      ClientMetaTableAccessor.getTableStopRowForMeta(table, type), type, maxRows, visitor);
  }

  private static void scanMeta(Connection connection, @Nullable final byte[] startRow,
    @Nullable final byte[] stopRow, QueryType type, final ClientMetaTableAccessor.Visitor visitor)
    throws IOException {
    scanMeta(connection, startRow, stopRow, type, Integer.MAX_VALUE, visitor);
  }

  /**
   * Performs a scan of META table for given table starting from given row.
   * @param connection connection we're using
   * @param visitor visitor to call
   * @param tableName table withing we scan
   * @param row start scan from this row
   * @param rowLimit max number of rows to return
   */
  public static void scanMeta(Connection connection, final ClientMetaTableAccessor.Visitor visitor,
    final TableName tableName, final byte[] row, final int rowLimit) throws IOException {
    byte[] startRow = null;
    byte[] stopRow = null;
    if (tableName != null) {
      startRow = ClientMetaTableAccessor.getTableStartRowForMeta(tableName, QueryType.REGION);
      if (row != null) {
        RegionInfo closestRi = getClosestRegionInfo(connection, tableName, row);
        startRow =
          RegionInfo.createRegionName(tableName, closestRi.getStartKey(), HConstants.ZEROES, false);
      }
      stopRow = ClientMetaTableAccessor.getTableStopRowForMeta(tableName, QueryType.REGION);
    }
    scanMeta(connection, startRow, stopRow, QueryType.REGION, rowLimit, visitor);
  }

  /**
   * Performs a scan of META table.
   * @param connection connection we're using
   * @param startRow Where to start the scan. Pass null if want to begin scan at first row.
   * @param stopRow Where to stop the scan. Pass null if want to scan all rows from the start one
   * @param type scanned part of meta
   * @param maxRows maximum rows to return
   * @param visitor Visitor invoked against each row.
   */
  static void scanMeta(Connection connection, @Nullable final byte[] startRow,
    @Nullable final byte[] stopRow, QueryType type, int maxRows,
    final ClientMetaTableAccessor.Visitor visitor) throws IOException {
    scanMeta(connection, startRow, stopRow, type, null, maxRows, visitor);
  }

  private static void scanMeta(Connection connection, @Nullable final byte[] startRow,
    @Nullable final byte[] stopRow, QueryType type, @Nullable Filter filter, int maxRows,
    final ClientMetaTableAccessor.Visitor visitor) throws IOException {
    int rowUpperLimit = maxRows > 0 ? maxRows : Integer.MAX_VALUE;
    Scan scan = getMetaScan(connection, rowUpperLimit);

    for (byte[] family : type.getFamilies()) {
      scan.addFamily(family);
    }
    if (startRow != null) {
      scan.withStartRow(startRow);
    }
    if (stopRow != null) {
      scan.withStopRow(stopRow);
    }
    if (filter != null) {
      scan.setFilter(filter);
    }

    if (LOG.isTraceEnabled()) {
      LOG.trace("Scanning META" + " starting at row=" + Bytes.toStringBinary(startRow) +
        " stopping at row=" + Bytes.toStringBinary(stopRow) + " for max=" + rowUpperLimit +
        " with caching=" + scan.getCaching());
    }

    int currentRow = 0;
    try (Table metaTable = getMetaHTable(connection)) {
      try (ResultScanner scanner = metaTable.getScanner(scan)) {
        Result data;
        while ((data = scanner.next()) != null) {
          if (data.isEmpty()) {
            continue;
          }
          // Break if visit returns false.
          if (!visitor.visit(data)) {
            break;
          }
          if (++currentRow >= rowUpperLimit) {
            break;
          }
        }
      }
    }
    if (visitor instanceof Closeable) {
      try {
        ((Closeable) visitor).close();
      } catch (Throwable t) {
        ExceptionUtil.rethrowIfInterrupt(t);
        LOG.debug("Got exception in closing the meta scanner visitor", t);
      }
    }
  }

  /**
   * @return Get closest metatable region row to passed <code>row</code>
   */
  @NonNull
  private static RegionInfo getClosestRegionInfo(Connection connection,
    @NonNull final TableName tableName, @NonNull final byte[] row) throws IOException {
    byte[] searchRow = RegionInfo.createRegionName(tableName, row, HConstants.NINES, false);
    Scan scan = getMetaScan(connection, 1);
    scan.setReversed(true);
    scan.withStartRow(searchRow);
    try (ResultScanner resultScanner = getMetaHTable(connection).getScanner(scan)) {
      Result result = resultScanner.next();
      if (result == null) {
        throw new TableNotFoundException("Cannot find row in META " + " for table: " + tableName +
          ", row=" + Bytes.toStringBinary(row));
      }
      RegionInfo regionInfo = CatalogFamilyFormat.getRegionInfo(result);
      if (regionInfo == null) {
        throw new IOException("RegionInfo was null or empty in Meta for " + tableName + ", row=" +
          Bytes.toStringBinary(row));
      }
      return regionInfo;
    }
  }

  /**
   * Returns the {@link ServerName} from catalog table {@link Result} where the region is
   * transitioning on. It should be the same as
   * {@link CatalogFamilyFormat#getServerName(Result,int)} if the server is at OPEN state.
   * @param r Result to pull the transitioning server name from
   * @return A ServerName instance or {@link CatalogFamilyFormat#getServerName(Result,int)} if
   *         necessary fields not found or empty.
   */
  @Nullable
  public static ServerName getTargetServerName(final Result r, final int replicaId) {
    final Cell cell = r.getColumnLatestCell(HConstants.CATALOG_FAMILY,
      CatalogFamilyFormat.getServerNameColumn(replicaId));
    if (cell == null || cell.getValueLength() == 0) {
      RegionLocations locations = CatalogFamilyFormat.getRegionLocations(r);
      if (locations != null) {
        HRegionLocation location = locations.getRegionLocation(replicaId);
        if (location != null) {
          return location.getServerName();
        }
      }
      return null;
    }
    return ServerName.parseServerName(
      Bytes.toString(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength()));
  }

  /**
   * Returns the daughter regions by reading the corresponding columns of the catalog table Result.
   * @param data a Result object from the catalog table scan
   * @return pair of RegionInfo or PairOfSameType(null, null) if region is not a split parent
   */
  public static PairOfSameType<RegionInfo> getDaughterRegions(Result data) {
    RegionInfo splitA = CatalogFamilyFormat.getRegionInfo(data, HConstants.SPLITA_QUALIFIER);
    RegionInfo splitB = CatalogFamilyFormat.getRegionInfo(data, HConstants.SPLITB_QUALIFIER);
    return new PairOfSameType<>(splitA, splitB);
  }

  /**
   * Fetch table state for given table from META table
   * @param conn connection to use
   * @param tableName table to fetch state for
   */
  @Nullable
  public static TableState getTableState(Connection conn, TableName tableName) throws IOException {
    if (tableName.equals(TableName.META_TABLE_NAME)) {
      return new TableState(tableName, TableState.State.ENABLED);
    }
    Table metaHTable = getMetaHTable(conn);
    Get get = new Get(tableName.getName()).addColumn(HConstants.TABLE_FAMILY,
      HConstants.TABLE_STATE_QUALIFIER);
    Result result = metaHTable.get(get);
    return CatalogFamilyFormat.getTableState(result);
  }

  /**
   * Fetch table states from META table
   * @param conn connection to use
   * @return map {tableName -&gt; state}
   */
  public static Map<TableName, TableState> getTableStates(Connection conn) throws IOException {
    final Map<TableName, TableState> states = new LinkedHashMap<>();
    ClientMetaTableAccessor.Visitor collector = r -> {
      TableState state = CatalogFamilyFormat.getTableState(r);
      if (state != null) {
        states.put(state.getTableName(), state);
      }
      return true;
    };
    fullScanTables(conn, collector);
    return states;
  }

  /**
   * Updates state in META Do not use. For internal use only.
   * @param conn connection to use
   * @param tableName table to look for
   */
  public static void updateTableState(Connection conn, TableName tableName, TableState.State actual)
    throws IOException {
    updateTableState(conn, new TableState(tableName, actual));
  }

  /**
   * Count regions in <code>hbase:meta</code> for passed table.
   * @param c Configuration object
   * @param tableName table name to count regions for
   * @return Count or regions in table <code>tableName</code>
   */
  public static int getRegionCount(final Configuration c, final TableName tableName)
    throws IOException {
    try (Connection connection = ConnectionFactory.createConnection(c)) {
      return getRegionCount(connection, tableName);
    }
  }

  /**
   * Count regions in <code>hbase:meta</code> for passed table.
   * @param connection Connection object
   * @param tableName table name to count regions for
   * @return Count or regions in table <code>tableName</code>
   */
  public static int getRegionCount(final Connection connection, final TableName tableName)
    throws IOException {
    try (RegionLocator locator = connection.getRegionLocator(tableName)) {
      List<HRegionLocation> locations = locator.getAllRegionLocations();
      return locations == null ? 0 : locations.size();
    }
  }

  ////////////////////////
  // Editing operations //
  ////////////////////////
  /**
   * Generates and returns a Put containing the region into for the catalog table
   */
  public static Put makePutFromRegionInfo(RegionInfo regionInfo, long ts) throws IOException {
    return addRegionInfo(new Put(regionInfo.getRegionName(), ts), regionInfo);
  }

  /**
   * Generates and returns a Delete containing the region info for the catalog table
   */
  private static Delete makeDeleteFromRegionInfo(RegionInfo regionInfo, long ts) {
    if (regionInfo == null) {
      throw new IllegalArgumentException("Can't make a delete for null region");
    }
    Delete delete = new Delete(regionInfo.getRegionName());
    delete.addFamily(HConstants.CATALOG_FAMILY, ts);
    return delete;
  }

  /**
   * Adds split daughters to the Put
   */
  private static Put addDaughtersToPut(Put put, RegionInfo splitA, RegionInfo splitB)
    throws IOException {
    if (splitA != null) {
      put.add(CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY).setRow(put.getRow())
        .setFamily(HConstants.CATALOG_FAMILY).setQualifier(HConstants.SPLITA_QUALIFIER)
        .setTimestamp(put.getTimestamp()).setType(Type.Put).setValue(RegionInfo.toByteArray(splitA))
        .build());
    }
    if (splitB != null) {
      put.add(CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY).setRow(put.getRow())
        .setFamily(HConstants.CATALOG_FAMILY).setQualifier(HConstants.SPLITB_QUALIFIER)
        .setTimestamp(put.getTimestamp()).setType(Type.Put).setValue(RegionInfo.toByteArray(splitB))
        .build());
    }
    return put;
  }

  /**
   * Put the passed <code>p</code> to the <code>hbase:meta</code> table.
   * @param connection connection we're using
   * @param p Put to add to hbase:meta
   */
  private static void putToMetaTable(Connection connection, Put p) throws IOException {
    try (Table table = getMetaHTable(connection)) {
      put(table, p);
    }
  }

  /**
   * @param t Table to use
   * @param p put to make
   */
  private static void put(Table t, Put p) throws IOException {
    debugLogMutation(p);
    t.put(p);
  }

  /**
   * Put the passed <code>ps</code> to the <code>hbase:meta</code> table.
   * @param connection connection we're using
   * @param ps Put to add to hbase:meta
   */
  public static void putsToMetaTable(final Connection connection, final List<Put> ps)
    throws IOException {
    if (ps.isEmpty()) {
      return;
    }
    try (Table t = getMetaHTable(connection)) {
      debugLogMutations(ps);
      // the implementation for putting a single Put is much simpler so here we do a check first.
      if (ps.size() == 1) {
        t.put(ps.get(0));
      } else {
        t.put(ps);
      }
    }
  }

  /**
   * Delete the passed <code>d</code> from the <code>hbase:meta</code> table.
   * @param connection connection we're using
   * @param d Delete to add to hbase:meta
   */
  private static void deleteFromMetaTable(final Connection connection, final Delete d)
    throws IOException {
    List<Delete> dels = new ArrayList<>(1);
    dels.add(d);
    deleteFromMetaTable(connection, dels);
  }

  /**
   * Delete the passed <code>deletes</code> from the <code>hbase:meta</code> table.
   * @param connection connection we're using
   * @param deletes Deletes to add to hbase:meta This list should support #remove.
   */
  private static void deleteFromMetaTable(final Connection connection, final List<Delete> deletes)
    throws IOException {
    try (Table t = getMetaHTable(connection)) {
      debugLogMutations(deletes);
      t.delete(deletes);
    }
  }

  /**
   * Deletes some replica columns corresponding to replicas for the passed rows
   * @param metaRows rows in hbase:meta
   * @param replicaIndexToDeleteFrom the replica ID we would start deleting from
   * @param numReplicasToRemove how many replicas to remove
   * @param connection connection we're using to access meta table
   */
  public static void removeRegionReplicasFromMeta(Set<byte[]> metaRows,
    int replicaIndexToDeleteFrom, int numReplicasToRemove, Connection connection)
    throws IOException {
    int absoluteIndex = replicaIndexToDeleteFrom + numReplicasToRemove;
    for (byte[] row : metaRows) {
      long now = EnvironmentEdgeManager.currentTime();
      Delete deleteReplicaLocations = new Delete(row);
      for (int i = replicaIndexToDeleteFrom; i < absoluteIndex; i++) {
        deleteReplicaLocations.addColumns(HConstants.CATALOG_FAMILY,
          CatalogFamilyFormat.getServerColumn(i), now);
        deleteReplicaLocations.addColumns(HConstants.CATALOG_FAMILY,
          CatalogFamilyFormat.getSeqNumColumn(i), now);
        deleteReplicaLocations.addColumns(HConstants.CATALOG_FAMILY,
          CatalogFamilyFormat.getStartCodeColumn(i), now);
        deleteReplicaLocations.addColumns(HConstants.CATALOG_FAMILY,
          CatalogFamilyFormat.getServerNameColumn(i), now);
        deleteReplicaLocations.addColumns(HConstants.CATALOG_FAMILY,
          CatalogFamilyFormat.getRegionStateColumn(i), now);
      }

      deleteFromMetaTable(connection, deleteReplicaLocations);
    }
  }

  private static Put addRegionStateToPut(Put put, RegionState.State state) throws IOException {
    put.add(CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY).setRow(put.getRow())
      .setFamily(HConstants.CATALOG_FAMILY).setQualifier(HConstants.STATE_QUALIFIER)
      .setTimestamp(put.getTimestamp()).setType(Cell.Type.Put).setValue(Bytes.toBytes(state.name()))
      .build());
    return put;
  }

  /**
   * Update state column in hbase:meta.
   */
  public static void updateRegionState(Connection connection, RegionInfo ri,
    RegionState.State state) throws IOException {
    Put put = new Put(RegionReplicaUtil.getRegionInfoForDefaultReplica(ri).getRegionName());
    putsToMetaTable(connection, Collections.singletonList(addRegionStateToPut(put, state)));
  }

  /**
   * Adds daughter region infos to hbase:meta row for the specified region. Note that this does not
   * add its daughter's as different rows, but adds information about the daughters in the same row
   * as the parent. Use
   * {@link #splitRegion(Connection, RegionInfo, long, RegionInfo, RegionInfo, ServerName, int)} if
   * you want to do that.
   * @param connection connection we're using
   * @param regionInfo RegionInfo of parent region
   * @param splitA first split daughter of the parent regionInfo
   * @param splitB second split daughter of the parent regionInfo
   * @throws IOException if problem connecting or updating meta
   */
  public static void addSplitsToParent(Connection connection, RegionInfo regionInfo,
    RegionInfo splitA, RegionInfo splitB) throws IOException {
    try (Table meta = getMetaHTable(connection)) {
      Put put = makePutFromRegionInfo(regionInfo, EnvironmentEdgeManager.currentTime());
      addDaughtersToPut(put, splitA, splitB);
      meta.put(put);
      debugLogMutation(put);
      LOG.debug("Added region {}", regionInfo.getRegionNameAsString());
    }
  }

  /**
   * Adds a (single) hbase:meta row for the specified new region and its daughters. Note that this
   * does not add its daughter's as different rows, but adds information about the daughters in the
   * same row as the parent. Use
   * {@link #splitRegion(Connection, RegionInfo, long, RegionInfo, RegionInfo, ServerName, int)} if
   * you want to do that.
   * @param connection connection we're using
   * @param regionInfo region information
   * @throws IOException if problem connecting or updating meta
   */
  @VisibleForTesting
  public static void addRegionToMeta(Connection connection, RegionInfo regionInfo)
    throws IOException {
    addRegionsToMeta(connection, Collections.singletonList(regionInfo), 1);
  }

  /**
   * Adds a hbase:meta row for each of the specified new regions. Initial state for new regions is
   * CLOSED.
   * @param connection connection we're using
   * @param regionInfos region information list
   * @throws IOException if problem connecting or updating meta
   */
  public static void addRegionsToMeta(Connection connection, List<RegionInfo> regionInfos,
    int regionReplication) throws IOException {
    addRegionsToMeta(connection, regionInfos, regionReplication,
      EnvironmentEdgeManager.currentTime());
  }

  /**
   * Adds a hbase:meta row for each of the specified new regions. Initial state for new regions is
   * CLOSED.
   * @param connection connection we're using
   * @param regionInfos region information list
   * @param ts desired timestamp
   * @throws IOException if problem connecting or updating meta
   */
  private static void addRegionsToMeta(Connection connection, List<RegionInfo> regionInfos,
    int regionReplication, long ts) throws IOException {
    List<Put> puts = new ArrayList<>();
    for (RegionInfo regionInfo : regionInfos) {
      if (RegionReplicaUtil.isDefaultReplica(regionInfo)) {
        Put put = makePutFromRegionInfo(regionInfo, ts);
        // New regions are added with initial state of CLOSED.
        addRegionStateToPut(put, RegionState.State.CLOSED);
        // Add empty locations for region replicas so that number of replicas can be cached
        // whenever the primary region is looked up from meta
        for (int i = 1; i < regionReplication; i++) {
          addEmptyLocation(put, i);
        }
        puts.add(put);
      }
    }
    putsToMetaTable(connection, puts);
    LOG.info("Added {} regions to meta.", puts.size());
  }

  @VisibleForTesting
  static Put addMergeRegions(Put put, Collection<RegionInfo> mergeRegions) throws IOException {
    int limit = 10000; // Arbitrary limit. No room in our formatted 'task0000' below for more.
    int max = mergeRegions.size();
    if (max > limit) {
      // Should never happen!!!!! But just in case.
      throw new RuntimeException(
        "Can't merge " + max + " regions in one go; " + limit + " is upper-limit.");
    }
    int counter = 0;
    for (RegionInfo ri : mergeRegions) {
      String qualifier = String.format(HConstants.MERGE_QUALIFIER_PREFIX_STR + "%04d", counter++);
      put.add(CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY).setRow(put.getRow())
        .setFamily(HConstants.CATALOG_FAMILY).setQualifier(Bytes.toBytes(qualifier))
        .setTimestamp(put.getTimestamp()).setType(Type.Put).setValue(RegionInfo.toByteArray(ri))
        .build());
    }
    return put;
  }

  /**
   * Merge regions into one in an atomic operation. Deletes the merging regions in hbase:meta and
   * adds the merged region.
   * @param connection connection we're using
   * @param mergedRegion the merged region
   * @param parentSeqNum Parent regions to merge and their next open sequence id used by serial
   *          replication. Set to -1 if not needed by this table.
   * @param sn the location of the region
   */
  public static void mergeRegions(Connection connection, RegionInfo mergedRegion,
    Map<RegionInfo, Long> parentSeqNum, ServerName sn, int regionReplication) throws IOException {
    long time = HConstants.LATEST_TIMESTAMP;
    List<Mutation> mutations = new ArrayList<>();
    List<RegionInfo> replicationParents = new ArrayList<>();
    for (Map.Entry<RegionInfo, Long> e : parentSeqNum.entrySet()) {
      RegionInfo ri = e.getKey();
      long seqNum = e.getValue();
      // Deletes for merging regions
      mutations.add(makeDeleteFromRegionInfo(ri, time));
      if (seqNum > 0) {
        mutations.add(makePutForReplicationBarrier(ri, seqNum, time));
        replicationParents.add(ri);
      }
    }
    // Put for parent
    Put putOfMerged = makePutFromRegionInfo(mergedRegion, time);
    putOfMerged = addMergeRegions(putOfMerged, parentSeqNum.keySet());
    // Set initial state to CLOSED.
    // NOTE: If initial state is not set to CLOSED then merged region gets added with the
    // default OFFLINE state. If Master gets restarted after this step, start up sequence of
    // master tries to assign this offline region. This is followed by re-assignments of the
    // merged region from resumed {@link MergeTableRegionsProcedure}
    addRegionStateToPut(putOfMerged, RegionState.State.CLOSED);
    mutations.add(putOfMerged);
    // The merged is a new region, openSeqNum = 1 is fine. ServerName may be null
    // if crash after merge happened but before we got to here.. means in-memory
    // locations of offlined merged, now-closed, regions is lost. Should be ok. We
    // assign the merged region later.
    if (sn != null) {
      addLocation(putOfMerged, sn, 1, mergedRegion.getReplicaId());
    }

    // Add empty locations for region replicas of the merged region so that number of replicas
    // can be cached whenever the primary region is looked up from meta
    for (int i = 1; i < regionReplication; i++) {
      addEmptyLocation(putOfMerged, i);
    }
    // add parent reference for serial replication
    if (!replicationParents.isEmpty()) {
      addReplicationParent(putOfMerged, replicationParents);
    }
    byte[] tableRow = Bytes.toBytes(mergedRegion.getRegionNameAsString() + HConstants.DELIMITER);
    multiMutate(connection, tableRow, mutations);
  }

  /**
   * Splits the region into two in an atomic operation. Offlines the parent region with the
   * information that it is split into two, and also adds the daughter regions. Does not add the
   * location information to the daughter regions since they are not open yet.
   * @param connection connection we're using
   * @param parent the parent region which is split
   * @param parentOpenSeqNum the next open sequence id for parent region, used by serial
   *          replication. -1 if not necessary.
   * @param splitA Split daughter region A
   * @param splitB Split daughter region B
   * @param sn the location of the region
   */
  public static void splitRegion(Connection connection, RegionInfo parent, long parentOpenSeqNum,
    RegionInfo splitA, RegionInfo splitB, ServerName sn, int regionReplication) throws IOException {
    long time = EnvironmentEdgeManager.currentTime();
    // Put for parent
    Put putParent = makePutFromRegionInfo(
      RegionInfoBuilder.newBuilder(parent).setOffline(true).setSplit(true).build(), time);
    addDaughtersToPut(putParent, splitA, splitB);

    // Puts for daughters
    Put putA = makePutFromRegionInfo(splitA, time);
    Put putB = makePutFromRegionInfo(splitB, time);
    if (parentOpenSeqNum > 0) {
      addReplicationBarrier(putParent, parentOpenSeqNum);
      addReplicationParent(putA, Collections.singletonList(parent));
      addReplicationParent(putB, Collections.singletonList(parent));
    }
    // Set initial state to CLOSED
    // NOTE: If initial state is not set to CLOSED then daughter regions get added with the
    // default OFFLINE state. If Master gets restarted after this step, start up sequence of
    // master tries to assign these offline regions. This is followed by re-assignments of the
    // daughter regions from resumed {@link SplitTableRegionProcedure}
    addRegionStateToPut(putA, RegionState.State.CLOSED);
    addRegionStateToPut(putB, RegionState.State.CLOSED);

    addSequenceNum(putA, 1, splitA.getReplicaId()); // new regions, openSeqNum = 1 is fine.
    addSequenceNum(putB, 1, splitB.getReplicaId());

    // Add empty locations for region replicas of daughters so that number of replicas can be
    // cached whenever the primary region is looked up from meta
    for (int i = 1; i < regionReplication; i++) {
      addEmptyLocation(putA, i);
      addEmptyLocation(putB, i);
    }

    byte[] tableRow = Bytes.toBytes(parent.getRegionNameAsString() + HConstants.DELIMITER);
    multiMutate(connection, tableRow, Arrays.asList(putParent, putA, putB));
  }

  /**
   * Update state of the table in meta.
   * @param connection what we use for update
   * @param state new state
   */
  private static void updateTableState(Connection connection, TableState state) throws IOException {
    Put put = makePutFromTableState(state, EnvironmentEdgeManager.currentTime());
    putToMetaTable(connection, put);
    LOG.info("Updated {} in hbase:meta", state);
  }

  /**
   * Construct PUT for given state
   * @param state new state
   */
  public static Put makePutFromTableState(TableState state, long ts) {
    Put put = new Put(state.getTableName().getName(), ts);
    put.addColumn(HConstants.TABLE_FAMILY, HConstants.TABLE_STATE_QUALIFIER,
      state.convert().toByteArray());
    return put;
  }

  /**
   * Remove state for table from meta
   * @param connection to use for deletion
   * @param table to delete state for
   */
  public static void deleteTableState(Connection connection, TableName table) throws IOException {
    long time = EnvironmentEdgeManager.currentTime();
    Delete delete = new Delete(table.getName());
    delete.addColumns(HConstants.TABLE_FAMILY, HConstants.TABLE_STATE_QUALIFIER, time);
    deleteFromMetaTable(connection, delete);
    LOG.info("Deleted table " + table + " state from META");
  }
  /**
   * Performs an atomic multi-mutate operation against the given table. Used by the likes of merge
   * and split as these want to make atomic mutations across multiple rows.
   * @throws IOException even if we encounter a RuntimeException, we'll still wrap it in an IOE.
   */
  private static void multiMutate(Connection conn, byte[] row, List<Mutation> mutations)
    throws IOException {
    debugLogMutations(mutations);
    MutateRowsRequest.Builder builder = MutateRowsRequest.newBuilder();
    for (Mutation mutation : mutations) {
      if (mutation instanceof Put) {
        builder.addMutationRequest(
          ProtobufUtil.toMutation(ClientProtos.MutationProto.MutationType.PUT, mutation));
      } else if (mutation instanceof Delete) {
        builder.addMutationRequest(
          ProtobufUtil.toMutation(ClientProtos.MutationProto.MutationType.DELETE, mutation));
      } else {
        throw new DoNotRetryIOException(
          "multi in MetaEditor doesn't support " + mutation.getClass().getName());
      }
    }
    MutateRowsRequest request = builder.build();
    AsyncTable<?> table = conn.toAsyncConnection().getTable(TableName.META_TABLE_NAME);
    CompletableFuture<MutateRowsResponse> future =
      table.<MultiRowMutationService, MutateRowsResponse> coprocessorService(
        MultiRowMutationService::newStub,
        (stub, controller, done) -> stub.mutateRows(controller, request, done), row);
    FutureUtils.get(future);
  }

  /**
   * Updates the location of the specified region in hbase:meta to be the specified server hostname
   * and startcode.
   * <p>
   * Uses passed catalog tracker to get a connection to the server hosting hbase:meta and makes
   * edits to that region.
   * @param connection connection we're using
   * @param regionInfo region to update location of
   * @param openSeqNum the latest sequence number obtained when the region was open
   * @param sn Server name
   * @param masterSystemTime wall clock time from master if passed in the open region RPC
   */
  @VisibleForTesting
  public static void updateRegionLocation(Connection connection, RegionInfo regionInfo,
    ServerName sn, long openSeqNum, long masterSystemTime) throws IOException {
    updateLocation(connection, regionInfo, sn, openSeqNum, masterSystemTime);
  }

  /**
   * Updates the location of the specified region to be the specified server.
   * <p>
   * Connects to the specified server which should be hosting the specified catalog region name to
   * perform the edit.
   * @param connection connection we're using
   * @param regionInfo region to update location of
   * @param sn Server name
   * @param openSeqNum the latest sequence number obtained when the region was open
   * @param masterSystemTime wall clock time from master if passed in the open region RPC
   * @throws IOException In particular could throw {@link java.net.ConnectException} if the server
   *           is down on other end.
   */
  private static void updateLocation(Connection connection, RegionInfo regionInfo, ServerName sn,
    long openSeqNum, long masterSystemTime) throws IOException {
    // region replicas are kept in the primary region's row
    Put put = new Put(CatalogFamilyFormat.getMetaKeyForRegion(regionInfo), masterSystemTime);
    addRegionInfo(put, regionInfo);
    addLocation(put, sn, openSeqNum, regionInfo.getReplicaId());
    putToMetaTable(connection, put);
    LOG.info("Updated row {} with server=", regionInfo.getRegionNameAsString(), sn);
  }

  /**
   * Deletes the specified region from META.
   * @param connection connection we're using
   * @param regionInfo region to be deleted from META
   */
  public static void deleteRegionInfo(Connection connection, RegionInfo regionInfo)
    throws IOException {
    Delete delete = new Delete(regionInfo.getRegionName());
    delete.addFamily(HConstants.CATALOG_FAMILY, HConstants.LATEST_TIMESTAMP);
    deleteFromMetaTable(connection, delete);
    LOG.info("Deleted " + regionInfo.getRegionNameAsString());
  }

  /**
   * Deletes the specified regions from META.
   * @param connection connection we're using
   * @param regionsInfo list of regions to be deleted from META
   */
  public static void deleteRegionInfos(Connection connection, List<RegionInfo> regionsInfo)
    throws IOException {
    deleteRegionInfos(connection, regionsInfo, EnvironmentEdgeManager.currentTime());
  }

  /**
   * Deletes the specified regions from META.
   * @param connection connection we're using
   * @param regionsInfo list of regions to be deleted from META
   */
  private static void deleteRegionInfos(Connection connection, List<RegionInfo> regionsInfo,
    long ts) throws IOException {
    List<Delete> deletes = new ArrayList<>(regionsInfo.size());
    for (RegionInfo hri : regionsInfo) {
      Delete e = new Delete(hri.getRegionName());
      e.addFamily(HConstants.CATALOG_FAMILY, ts);
      deletes.add(e);
    }
    deleteFromMetaTable(connection, deletes);
    LOG.info("Deleted {} regions from META", regionsInfo.size());
    LOG.debug("Deleted regions: {}", regionsInfo);
  }

  /**
   * Overwrites the specified regions from hbase:meta. Deletes old rows for the given regions and
   * adds new ones. Regions added back have state CLOSED.
   * @param connection connection we're using
   * @param regionInfos list of regions to be added to META
   */
  public static void overwriteRegions(Connection connection, List<RegionInfo> regionInfos,
    int regionReplication) throws IOException {
    // use master time for delete marker and the Put
    long now = EnvironmentEdgeManager.currentTime();
    deleteRegionInfos(connection, regionInfos, now);
    // Why sleep? This is the easiest way to ensure that the previous deletes does not
    // eclipse the following puts, that might happen in the same ts from the server.
    // See HBASE-9906, and HBASE-9879. Once either HBASE-9879, HBASE-8770 is fixed,
    // or HBASE-9905 is fixed and meta uses seqIds, we do not need the sleep.
    //
    // HBASE-13875 uses master timestamp for the mutations. The 20ms sleep is not needed
    addRegionsToMeta(connection, regionInfos, regionReplication, now + 1);
    LOG.info("Overwritten " + regionInfos.size() + " regions to Meta");
    LOG.debug("Overwritten regions: {} ", regionInfos);
  }

  /**
   * Deletes merge qualifiers for the specified merge region.
   * @param connection connection we're using
   * @param mergeRegion the merged region
   */
  public static void deleteMergeQualifiers(Connection connection, final RegionInfo mergeRegion)
    throws IOException {
    Delete delete = new Delete(mergeRegion.getRegionName());
    // NOTE: We are doing a new hbase:meta read here.
    Cell[] cells = getRegionResult(connection, mergeRegion.getRegionName()).rawCells();
    if (cells == null || cells.length == 0) {
      return;
    }
    List<byte[]> qualifiers = new ArrayList<>();
    for (Cell cell : cells) {
      if (!isMergeQualifierPrefix(cell)) {
        continue;
      }
      byte[] qualifier = CellUtil.cloneQualifier(cell);
      qualifiers.add(qualifier);
      delete.addColumns(HConstants.CATALOG_FAMILY, qualifier, HConstants.LATEST_TIMESTAMP);
    }

    // There will be race condition that a GCMultipleMergedRegionsProcedure is scheduled while
    // the previous GCMultipleMergedRegionsProcedure is still going on, in this case, the second
    // GCMultipleMergedRegionsProcedure could delete the merged region by accident!
    if (qualifiers.isEmpty()) {
      LOG.info("No merged qualifiers for region " + mergeRegion.getRegionNameAsString() +
        " in meta table, they are cleaned up already, Skip.");
      return;
    }

    deleteFromMetaTable(connection, delete);
    LOG.info("Deleted merge references in " + mergeRegion.getRegionNameAsString() +
      ", deleted qualifiers " +
      qualifiers.stream().map(Bytes::toStringBinary).collect(Collectors.joining(", ")));
  }

  public static Put addRegionInfo(final Put p, final RegionInfo hri) throws IOException {
    p.add(CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY).setRow(p.getRow())
      .setFamily(HConstants.CATALOG_FAMILY).setQualifier(HConstants.REGIONINFO_QUALIFIER)
      .setTimestamp(p.getTimestamp()).setType(Type.Put)
      // Serialize the Default Replica HRI otherwise scan of hbase:meta
      // shows an info:regioninfo value with encoded name and region
      // name that differs from that of the hbase;meta row.
      .setValue(RegionInfo.toByteArray(RegionReplicaUtil.getRegionInfoForDefaultReplica(hri)))
      .build());
    return p;
  }

  public static Put addLocation(Put p, ServerName sn, long openSeqNum, int replicaId)
    throws IOException {
    CellBuilder builder = CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY);
    return p
      .add(builder.clear().setRow(p.getRow()).setFamily(HConstants.CATALOG_FAMILY)
        .setQualifier(CatalogFamilyFormat.getServerColumn(replicaId)).setTimestamp(p.getTimestamp())
        .setType(Cell.Type.Put).setValue(Bytes.toBytes(sn.getAddress().toString())).build())
      .add(builder.clear().setRow(p.getRow()).setFamily(HConstants.CATALOG_FAMILY)
        .setQualifier(CatalogFamilyFormat.getStartCodeColumn(replicaId))
        .setTimestamp(p.getTimestamp()).setType(Cell.Type.Put)
        .setValue(Bytes.toBytes(sn.getStartcode())).build())
      .add(builder.clear().setRow(p.getRow()).setFamily(HConstants.CATALOG_FAMILY)
        .setQualifier(CatalogFamilyFormat.getSeqNumColumn(replicaId)).setTimestamp(p.getTimestamp())
        .setType(Type.Put).setValue(Bytes.toBytes(openSeqNum)).build());
  }

  private static void writeRegionName(ByteArrayOutputStream out, byte[] regionName) {
    for (byte b : regionName) {
      if (b == ESCAPE_BYTE) {
        out.write(ESCAPE_BYTE);
      }
      out.write(b);
    }
  }

  @VisibleForTesting
  public static byte[] getParentsBytes(List<RegionInfo> parents) {
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    Iterator<RegionInfo> iter = parents.iterator();
    writeRegionName(bos, iter.next().getRegionName());
    while (iter.hasNext()) {
      bos.write(ESCAPE_BYTE);
      bos.write(SEPARATED_BYTE);
      writeRegionName(bos, iter.next().getRegionName());
    }
    return bos.toByteArray();
  }

  private static List<byte[]> parseParentsBytes(byte[] bytes) {
    List<byte[]> parents = new ArrayList<>();
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] == ESCAPE_BYTE) {
        i++;
        if (bytes[i] == SEPARATED_BYTE) {
          parents.add(bos.toByteArray());
          bos.reset();
          continue;
        }
        // fall through to append the byte
      }
      bos.write(bytes[i]);
    }
    if (bos.size() > 0) {
      parents.add(bos.toByteArray());
    }
    return parents;
  }

  private static void addReplicationParent(Put put, List<RegionInfo> parents) throws IOException {
    byte[] value = getParentsBytes(parents);
    put.add(CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY).setRow(put.getRow())
      .setFamily(HConstants.REPLICATION_BARRIER_FAMILY).setQualifier(REPLICATION_PARENT_QUALIFIER)
      .setTimestamp(put.getTimestamp()).setType(Type.Put).setValue(value).build());
  }

  public static Put makePutForReplicationBarrier(RegionInfo regionInfo, long openSeqNum, long ts)
    throws IOException {
    Put put = new Put(regionInfo.getRegionName(), ts);
    addReplicationBarrier(put, openSeqNum);
    return put;
  }

  /**
   * See class comment on SerialReplicationChecker
   */
  public static void addReplicationBarrier(Put put, long openSeqNum) throws IOException {
    put.add(CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY).setRow(put.getRow())
      .setFamily(HConstants.REPLICATION_BARRIER_FAMILY).setQualifier(HConstants.SEQNUM_QUALIFIER)
      .setTimestamp(put.getTimestamp()).setType(Type.Put).setValue(Bytes.toBytes(openSeqNum))
      .build());
  }

  private static Put addEmptyLocation(Put p, int replicaId) throws IOException {
    CellBuilder builder = CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY);
    return p
      .add(builder.clear().setRow(p.getRow()).setFamily(HConstants.CATALOG_FAMILY)
        .setQualifier(CatalogFamilyFormat.getServerColumn(replicaId)).setTimestamp(p.getTimestamp())
        .setType(Type.Put).build())
      .add(builder.clear().setRow(p.getRow()).setFamily(HConstants.CATALOG_FAMILY)
        .setQualifier(CatalogFamilyFormat.getStartCodeColumn(replicaId))
        .setTimestamp(p.getTimestamp()).setType(Cell.Type.Put).build())
      .add(builder.clear().setRow(p.getRow()).setFamily(HConstants.CATALOG_FAMILY)
        .setQualifier(CatalogFamilyFormat.getSeqNumColumn(replicaId)).setTimestamp(p.getTimestamp())
        .setType(Cell.Type.Put).build());
  }

  public static final class ReplicationBarrierResult {
    private final long[] barriers;
    private final RegionState.State state;
    private final List<byte[]> parentRegionNames;

    ReplicationBarrierResult(long[] barriers, State state, List<byte[]> parentRegionNames) {
      this.barriers = barriers;
      this.state = state;
      this.parentRegionNames = parentRegionNames;
    }

    public long[] getBarriers() {
      return barriers;
    }

    public RegionState.State getState() {
      return state;
    }

    public List<byte[]> getParentRegionNames() {
      return parentRegionNames;
    }

    @Override
    public String toString() {
      return "ReplicationBarrierResult [barriers=" + Arrays.toString(barriers) + ", state=" +
        state + ", parentRegionNames=" +
        parentRegionNames.stream().map(Bytes::toStringBinary).collect(Collectors.joining(", ")) +
        "]";
    }
  }

  private static long getReplicationBarrier(Cell c) {
    return Bytes.toLong(c.getValueArray(), c.getValueOffset(), c.getValueLength());
  }

  public static long[] getReplicationBarriers(Result result) {
    return result.getColumnCells(HConstants.REPLICATION_BARRIER_FAMILY, HConstants.SEQNUM_QUALIFIER)
      .stream().mapToLong(MetaTableAccessor::getReplicationBarrier).sorted().distinct().toArray();
  }

  private static ReplicationBarrierResult getReplicationBarrierResult(Result result) {
    long[] barriers = getReplicationBarriers(result);
    byte[] stateBytes = result.getValue(HConstants.CATALOG_FAMILY, HConstants.STATE_QUALIFIER);
    RegionState.State state =
      stateBytes != null ? RegionState.State.valueOf(Bytes.toString(stateBytes)) : null;
    byte[] parentRegionsBytes =
      result.getValue(HConstants.REPLICATION_BARRIER_FAMILY, REPLICATION_PARENT_QUALIFIER);
    List<byte[]> parentRegionNames =
      parentRegionsBytes != null ? parseParentsBytes(parentRegionsBytes) : Collections.emptyList();
    return new ReplicationBarrierResult(barriers, state, parentRegionNames);
  }

  public static ReplicationBarrierResult getReplicationBarrierResult(Connection conn,
    TableName tableName, byte[] row, byte[] encodedRegionName) throws IOException {
    byte[] metaStartKey = RegionInfo.createRegionName(tableName, row, HConstants.NINES, false);
    byte[] metaStopKey =
      RegionInfo.createRegionName(tableName, HConstants.EMPTY_START_ROW, "", false);
    Scan scan = new Scan().withStartRow(metaStartKey).withStopRow(metaStopKey)
      .addColumn(HConstants.CATALOG_FAMILY, HConstants.STATE_QUALIFIER)
      .addFamily(HConstants.REPLICATION_BARRIER_FAMILY).readAllVersions().setReversed(true)
      .setCaching(10);
    try (Table table = getMetaHTable(conn); ResultScanner scanner = table.getScanner(scan)) {
      for (Result result;;) {
        result = scanner.next();
        if (result == null) {
          return new ReplicationBarrierResult(new long[0], null, Collections.emptyList());
        }
        byte[] regionName = result.getRow();
        // TODO: we may look up a region which has already been split or merged so we need to check
        // whether the encoded name matches. Need to find a way to quit earlier when there is no
        // record for the given region, for now it will scan to the end of the table.
        if (!Bytes.equals(encodedRegionName,
          Bytes.toBytes(RegionInfo.encodeRegionName(regionName)))) {
          continue;
        }
        return getReplicationBarrierResult(result);
      }
    }
  }

  public static long[] getReplicationBarrier(Connection conn, byte[] regionName)
    throws IOException {
    try (Table table = getMetaHTable(conn)) {
      Result result = table.get(new Get(regionName)
        .addColumn(HConstants.REPLICATION_BARRIER_FAMILY, HConstants.SEQNUM_QUALIFIER)
        .readAllVersions());
      return getReplicationBarriers(result);
    }
  }

  public static List<Pair<String, Long>> getTableEncodedRegionNameAndLastBarrier(Connection conn,
    TableName tableName) throws IOException {
    List<Pair<String, Long>> list = new ArrayList<>();
    scanMeta(conn,
      ClientMetaTableAccessor.getTableStartRowForMeta(tableName, QueryType.REPLICATION),
      ClientMetaTableAccessor.getTableStopRowForMeta(tableName, QueryType.REPLICATION),
      QueryType.REPLICATION, r -> {
        byte[] value =
          r.getValue(HConstants.REPLICATION_BARRIER_FAMILY, HConstants.SEQNUM_QUALIFIER);
        if (value == null) {
          return true;
        }
        long lastBarrier = Bytes.toLong(value);
        String encodedRegionName = RegionInfo.encodeRegionName(r.getRow());
        list.add(Pair.newPair(encodedRegionName, lastBarrier));
        return true;
      });
    return list;
  }

  public static List<String> getTableEncodedRegionNamesForSerialReplication(Connection conn,
    TableName tableName) throws IOException {
    List<String> list = new ArrayList<>();
    scanMeta(conn,
      ClientMetaTableAccessor.getTableStartRowForMeta(tableName, QueryType.REPLICATION),
      ClientMetaTableAccessor.getTableStopRowForMeta(tableName, QueryType.REPLICATION),
      QueryType.REPLICATION, new FirstKeyOnlyFilter(), Integer.MAX_VALUE, r -> {
        list.add(RegionInfo.encodeRegionName(r.getRow()));
        return true;
      });
    return list;
  }

  private static void debugLogMutations(List<? extends Mutation> mutations) throws IOException {
    if (!METALOG.isDebugEnabled()) {
      return;
    }
    // Logging each mutation in separate line makes it easier to see diff between them visually
    // because of common starting indentation.
    for (Mutation mutation : mutations) {
      debugLogMutation(mutation);
    }
  }

  private static void debugLogMutation(Mutation p) throws IOException {
    METALOG.debug("{} {}", p.getClass().getSimpleName(), p.toJSON());
  }

  private static Put addSequenceNum(Put p, long openSeqNum, int replicaId) throws IOException {
    return p.add(CellBuilderFactory.create(CellBuilderType.SHALLOW_COPY).setRow(p.getRow())
      .setFamily(HConstants.CATALOG_FAMILY)
      .setQualifier(CatalogFamilyFormat.getSeqNumColumn(replicaId)).setTimestamp(p.getTimestamp())
      .setType(Type.Put).setValue(Bytes.toBytes(openSeqNum)).build());
  }
}
