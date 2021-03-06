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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.metadata;

import org.apache.iotdb.db.conf.IoTDBConfig;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.conf.adapter.ActiveTimeSeriesCounter;
import org.apache.iotdb.db.conf.adapter.IoTDBConfigDynamicAdapter;
import org.apache.iotdb.db.engine.StorageEngine;
import org.apache.iotdb.db.engine.fileSystem.SystemFileFactory;
import org.apache.iotdb.db.exception.ConfigAdjusterException;
import org.apache.iotdb.db.exception.metadata.IllegalPathException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.metadata.PathNotExistException;
import org.apache.iotdb.db.exception.metadata.StorageGroupAlreadySetException;
import org.apache.iotdb.db.exception.metadata.StorageGroupNotSetException;
import org.apache.iotdb.db.metadata.mnode.InternalMNode;
import org.apache.iotdb.db.metadata.mnode.LeafMNode;
import org.apache.iotdb.db.metadata.mnode.MNode;
import org.apache.iotdb.db.metadata.mnode.StorageGroupMNode;
import org.apache.iotdb.db.monitor.MonitorConstants;
import org.apache.iotdb.db.qp.constant.SQLConstant;
import org.apache.iotdb.db.qp.physical.sys.CreateTimeSeriesPlan;
import org.apache.iotdb.db.qp.physical.sys.ShowTimeSeriesPlan;
import org.apache.iotdb.db.query.dataset.ShowTimeSeriesResult;
import org.apache.iotdb.db.utils.RandomDeleteCache;
import org.apache.iotdb.db.utils.TestOnly;
import org.apache.iotdb.tsfile.exception.cache.CacheException;
import org.apache.iotdb.tsfile.file.metadata.enums.CompressionType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.utils.Pair;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * This class takes the responsibility of serialization of all the metadata info and persistent it
 * into files. This class contains all the interfaces to modify the metadata for delta system. All
 * the operations will be insert into the logs temporary in case the downtime of the delta system.
 */
public class MManager {

  private static final Logger logger = LoggerFactory.getLogger(MManager.class);
  private static final String TIME_SERIES_TREE_HEADER = "===  Timeseries Tree  ===\n\n";

  // the lock for read/insert
  private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  // the log file seriesPath
  private String logFilePath;
  private MTree mtree;
  private MLogWriter logWriter;
  private TagLogFile tagLogFile;
  private boolean writeToLog;
  // device -> DeviceMNode
  private RandomDeleteCache<String, MNode> mNodeCache;

  // tag key -> tag value -> LeafMNode
  private Map<String, Map<String, Set<LeafMNode>>> tagIndex = new HashMap<>();

  private Map<String, Integer> seriesNumberInStorageGroups = new HashMap<>();
  private long maxSeriesNumberAmongStorageGroup;
  private boolean initialized;
  private IoTDBConfig config;

  private static class MManagerHolder {
    private MManagerHolder() {
      //allowed to do nothing
    }

    private static final MManager INSTANCE = new MManager();
  }

  private MManager() {
    config = IoTDBDescriptor.getInstance().getConfig();
    String schemaDir = config.getSchemaDir();
    File schemaFolder = SystemFileFactory.INSTANCE.getFile(schemaDir);
    if (!schemaFolder.exists()) {
      if (schemaFolder.mkdirs()) {
        logger.info("create system folder {}", schemaFolder.getAbsolutePath());
      } else {
        logger.info("create system folder {} failed.", schemaFolder.getAbsolutePath());
      }
    }
    logFilePath = schemaDir + File.separator + MetadataConstant.METADATA_LOG;

    // do not write log when recover
    writeToLog = false;

    int cacheSize = config.getmManagerCacheSize();
    mNodeCache = new RandomDeleteCache<String, MNode>(cacheSize) {

      @Override
      public MNode loadObjectByKey(String key) throws CacheException {
        lock.readLock().lock();
        try {
          return mtree.getNodeByPathWithStorageGroupCheck(key);
        } catch (MetadataException e) {
          throw new CacheException(e);
        } finally {
          lock.readLock().unlock();
        }
      }
    };
  }

  public static MManager getInstance() {
    return MManagerHolder.INSTANCE;
  }

  //Because the writer will be used later and should not be closed here.
  @SuppressWarnings("squid:S2093")
  public synchronized void init() {
    if (initialized) {
      return;
    }
    File logFile = SystemFileFactory.INSTANCE.getFile(logFilePath);

    try {
      tagLogFile = new TagLogFile(config.getSchemaDir(), MetadataConstant.TAG_LOG);

      if (config.isEnableParameterAdapter()) {
        // storage group name -> the series number
        seriesNumberInStorageGroups = new HashMap<>();
      }

      initFromLog(logFile);

      if (config.isEnableParameterAdapter()) {
        List<String> storageGroups = mtree.getAllStorageGroupNames();
        for (String sg : storageGroups) {
          MNode node = mtree.getNodeByPath(sg);
          seriesNumberInStorageGroups.put(sg, node.getLeafCount());
        }
        if (seriesNumberInStorageGroups.isEmpty()) {
          maxSeriesNumberAmongStorageGroup = 0;
        } else {
          maxSeriesNumberAmongStorageGroup = seriesNumberInStorageGroups.values().stream()
              .max(Integer::compareTo).get();
        }
      }

      logWriter = new MLogWriter(config.getSchemaDir(), MetadataConstant.METADATA_LOG);
      writeToLog = true;
    } catch (IOException | MetadataException e) {
      mtree = new MTree();
      logger.error("Cannot read MTree from file, using an empty new one", e);
    }
    initialized = true;
  }

  private void initFromLog(File logFile) throws IOException {
    // init the metadata from the operation log
    mtree = new MTree();
    if (logFile.exists()) {
      try (FileReader fr = new FileReader(logFile);
          BufferedReader br = new BufferedReader(fr)) {
        String cmd;
        while ((cmd = br.readLine()) != null) {
          try {
            operation(cmd);
          } catch (Exception e) {
            logger.error("Can not operate cmd {}", cmd, e);
          }
        }
      }
    }
  }

  /**
   * function for clearing MTree
   */
  public void clear() {
    lock.writeLock().lock();
    try {
      this.mtree = new MTree();
      this.mNodeCache.clear();
      this.tagIndex.clear();
      if (seriesNumberInStorageGroups != null) {
        this.seriesNumberInStorageGroups.clear();
      }
      this.maxSeriesNumberAmongStorageGroup = 0;
      if (logWriter != null) {
        logWriter.close();
        logWriter = null;
      }
      if (tagLogFile != null) {
        tagLogFile.close();
        tagLogFile = null;
      }
      initialized = false;
    } catch (IOException e) {
      logger.error("Cannot close metadata log writer, because:", e);
    } finally {
      lock.writeLock().unlock();
    }
  }

  public void operation(String cmd) throws IOException, MetadataException {
    //see createTimeseries() to get the detailed format of the cmd
    String[] args = cmd.trim().split(",", -1);
    switch (args[0]) {
      case MetadataOperationType.CREATE_TIMESERIES:
        Map<String, String> props = new HashMap<>();
        if (!args[5].isEmpty()){
          String[] keyValues = args[5].split("&");
          String[] kv;
          for (String keyValue : keyValues) {
            kv = keyValue.split("=");
            props.put(kv[0], kv[1]);
          }
        }

        String alias = null;
        if (!args[6].isEmpty()) {
          alias = args[6];
        }
        long offset = -1L;
        Map<String, String>  tagMap = null;
        if (!args[7].isEmpty()) {
          offset = Long.parseLong(args[7]);
          tagMap = tagLogFile.readTag(config.getTagAttributeTotalSize(), offset);
        }

        CreateTimeSeriesPlan plan = new CreateTimeSeriesPlan(new Path(args[1]),
            TSDataType.deserialize(Short.parseShort(args[2])),
            TSEncoding.deserialize(Short.parseShort(args[3])),
            CompressionType.deserialize(Short.parseShort(args[4])),
            props, tagMap, null, alias);
        createTimeseries(plan, offset);
        break;
      case MetadataOperationType.DELETE_TIMESERIES:
        for (String deleteStorageGroup : deleteTimeseries(args[1])) {
          StorageEngine.getInstance().deleteAllDataFilesInOneStorageGroup(deleteStorageGroup);
        }
        break;
      case MetadataOperationType.SET_STORAGE_GROUP:
        setStorageGroup(args[1]);
        break;
      case MetadataOperationType.DELETE_STORAGE_GROUP:
        List<String> storageGroups = new ArrayList<>();
        storageGroups.addAll(Arrays.asList(args).subList(1, args.length));
        deleteStorageGroups(storageGroups);
        break;
      case MetadataOperationType.SET_TTL:
        setTTL(args[1], Long.parseLong(args[2]));
        break;
      default:
        logger.error("Unrecognizable command {}", cmd);
    }
  }

  public void createTimeseries(CreateTimeSeriesPlan plan) throws MetadataException {
    createTimeseries(plan, -1);
  }

  public void createTimeseries(CreateTimeSeriesPlan plan, long offset) throws MetadataException {
    lock.writeLock().lock();
    String path = plan.getPath().getFullPath();
    try {
      /*
       * get the storage group with auto create schema
       */
      String storageGroupName;
      try {
        storageGroupName = mtree.getStorageGroupName(path);
      } catch (StorageGroupNotSetException e) {
        if (!config.isAutoCreateSchemaEnabled()) {
          throw e;
        }
        storageGroupName = MetaUtils.getStorageGroupNameByLevel(path,
            config.getDefaultStorageGroupLevel());
        setStorageGroup(storageGroupName);
      }

      // create time series in MTree
      LeafMNode leafMNode = mtree.createTimeseries(path, plan.getDataType(), plan.getEncoding(),
          plan.getCompressor(), plan.getProps(), plan.getAlias());
      try {
        // check memory
        IoTDBConfigDynamicAdapter.getInstance().addOrDeleteTimeSeries(1);
      } catch (ConfigAdjusterException e) {
        removeFromTagInvertedIndex(mtree.deleteTimeseriesAndReturnEmptyStorageGroup(path).right);
        throw e;
      }

      // write log
      if (writeToLog) {
        // either tags or attributes is not empty
        if ((plan.getTags() != null && !plan.getTags().isEmpty()) || (plan.getAttributes() != null
            && !plan.getAttributes().isEmpty())) {
          offset = tagLogFile.write(plan.getTags(), plan.getAttributes());
        }
        logWriter.createTimeseries(plan, offset);
      }
      leafMNode.setOffset(offset);

      // update tag index
      if (plan.getTags() != null) {
        // tag key, tag value
        for (Entry<String, String> entry : plan.getTags().entrySet()) {
          tagIndex.computeIfAbsent(entry.getKey(), k -> new HashMap<>())
              .computeIfAbsent(entry.getValue(), v -> new HashSet<>())
              .add(leafMNode);
        }
      }

      // update statistics
      if (config.isEnableParameterAdapter()) {
        int size = seriesNumberInStorageGroups.get(storageGroupName);
        seriesNumberInStorageGroups.put(storageGroupName, size + 1);
        if (size + 1 > maxSeriesNumberAmongStorageGroup) {
          maxSeriesNumberAmongStorageGroup = size + 1;
        }
      }
    } catch (IOException | ConfigAdjusterException e) {
      throw new MetadataException(e.getMessage());
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Add one timeseries to metadata tree, if the timeseries already exists, throw exception
   *
   * @param path the timeseries path
   * @param dataType the dateType {@code DataType} of the timeseries
   * @param encoding the encoding function {@code Encoding} of the timeseries
   * @param compressor the compressor function {@code Compressor} of the time series
   * @return whether the measurement occurs for the first time in this storage group (if true, the
   * measurement should be registered to the StorageEngine too)
   */
  public void createTimeseries(String path, TSDataType dataType, TSEncoding encoding,
      CompressionType compressor, Map<String, String> props) throws MetadataException {
    createTimeseries(
        new CreateTimeSeriesPlan(new Path(path), dataType, encoding, compressor, props, null, null,
            null));
  }


  /**
   * Delete all timeseries under the given path, may cross different storage group
   *
   * @param prefixPath path to be deleted, could be root or a prefix path or a full path
   * @return a set contains StorageGroups that contain no more timeseries after this deletion and
   * files of such StorageGroups should be deleted to reclaim disk space.
   */
  public Set<String> deleteTimeseries(String prefixPath) throws MetadataException {
    lock.writeLock().lock();
    if (isStorageGroup(prefixPath)) {

      if (config.isEnableParameterAdapter()) {
        int size = seriesNumberInStorageGroups.get(prefixPath);
        seriesNumberInStorageGroups.put(prefixPath, 0);
        if (size == maxSeriesNumberAmongStorageGroup) {
          seriesNumberInStorageGroups.values().stream().max(Integer::compareTo)
              .ifPresent(val -> maxSeriesNumberAmongStorageGroup = val);
        }
      }

      mNodeCache.clear();
    }
    try {
      Set<String> emptyStorageGroups = new HashSet<>();

      List<String> allTimeseries = mtree.getAllTimeseriesName(prefixPath);
      // Monitor storage group seriesPath is not allowed to be deleted
      allTimeseries.removeIf(p -> p.startsWith(MonitorConstants.STAT_STORAGE_GROUP_PREFIX));

      for (String p : allTimeseries) {
        String emptyStorageGroup = deleteOneTimeseriesAndUpdateStatisticsAndLog(p);
        if (emptyStorageGroup != null) {
          emptyStorageGroups.add(emptyStorageGroup);
        }
      }
      return emptyStorageGroups;
    } catch (IOException e) {
      throw new MetadataException(e.getMessage());
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * remove the node from the tag inverted index
   * @param node
   * @throws IOException
   */
  private void removeFromTagInvertedIndex(LeafMNode node) throws IOException {
    if (node.getOffset() < 0) {
      return;
    }
    Map<String, String> tagMap = tagLogFile.readTag(config.getTagAttributeTotalSize(), node.getOffset());
    if (tagMap != null) {
      for (Entry<String, String> entry : tagMap.entrySet()) {
        tagIndex.get(entry.getKey()).get(entry.getValue()).remove(node);
      }
    }
  }

  /**
   * @param path full path from root to leaf node
   * @return after delete if the storage group is empty, return its name, otherwise return null
   */
  private String deleteOneTimeseriesAndUpdateStatisticsAndLog(String path)
      throws MetadataException, IOException {
    lock.writeLock().lock();
    try {
      Pair<String, LeafMNode> pair = mtree.deleteTimeseriesAndReturnEmptyStorageGroup(path);
      removeFromTagInvertedIndex(pair.right);
      String storageGroupName = pair.left;
      if (writeToLog) {
        logWriter.deleteTimeseries(path);
      }
      // TODO: delete the path node and all its ancestors
      mNodeCache.clear();
      try {
        IoTDBConfigDynamicAdapter.getInstance().addOrDeleteTimeSeries(-1);
      } catch (ConfigAdjusterException e) {
        throw new MetadataException(e);
      }

      if (config.isEnableParameterAdapter()) {
        String storageGroup = getStorageGroupName(path);
        int size = seriesNumberInStorageGroups.get(storageGroup);
        seriesNumberInStorageGroups.put(storageGroup, size - 1);
        if (size == maxSeriesNumberAmongStorageGroup) {
          seriesNumberInStorageGroups.values().stream().max(Integer::compareTo)
              .ifPresent(val -> maxSeriesNumberAmongStorageGroup = val);
        }
      }
      return storageGroupName;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Set storage group of the given path to MTree. Check
   *
   * @param storageGroup root.node.(node)*
   */
  public void setStorageGroup(String storageGroup) throws MetadataException {
    lock.writeLock().lock();
    try {
      mtree.setStorageGroup(storageGroup);
      if (writeToLog) {
        logWriter.setStorageGroup(storageGroup);
      }
      IoTDBConfigDynamicAdapter.getInstance().addOrDeleteStorageGroup(1);

      if (config.isEnableParameterAdapter()) {
        ActiveTimeSeriesCounter.getInstance().init(storageGroup);
        seriesNumberInStorageGroups.put(storageGroup, 0);
      }
    } catch (IOException e) {
      throw new MetadataException(e.getMessage());
    } catch (ConfigAdjusterException e) {
      mtree.deleteStorageGroup(storageGroup);
      throw new MetadataException(e);
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Delete storage groups of given paths from MTree. Log format: "delete_storage_group,sg1,sg2,sg3"
   *
   * @param storageGroups list of paths to be deleted. Format: root.node
   */
  public void deleteStorageGroups(List<String> storageGroups) throws MetadataException {
    lock.writeLock().lock();
    try {
      for (String storageGroup : storageGroups) {
        // try to delete storage group
        mtree.deleteStorageGroup(storageGroup);

        // if success
        if (writeToLog) {
          logWriter.deleteStorageGroup(storageGroup);
        }
        mNodeCache.clear();

        if (config.isEnableParameterAdapter()) {
          IoTDBConfigDynamicAdapter.getInstance().addOrDeleteStorageGroup(-1);
          int size = seriesNumberInStorageGroups.get(storageGroup);
          IoTDBConfigDynamicAdapter.getInstance().addOrDeleteTimeSeries(size * -1);
          ActiveTimeSeriesCounter.getInstance().delete(storageGroup);
          seriesNumberInStorageGroups.remove(storageGroup);
          if (size == maxSeriesNumberAmongStorageGroup) {
            if (seriesNumberInStorageGroups.isEmpty()) {
              maxSeriesNumberAmongStorageGroup = 0;
            } else {
              maxSeriesNumberAmongStorageGroup = seriesNumberInStorageGroups.values().stream()
                  .max(Integer::compareTo).get();
            }
          }
        }
      }
    } catch (ConfigAdjusterException e) {
      throw new MetadataException(e);
    } catch (IOException e) {
      throw new MetadataException(e.getMessage());
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Check if the given path is storage group or not.
   *
   * @param path Format: root.node.(node)*
   * @apiNote :for cluster
   */
  boolean isStorageGroup(String path) {
    lock.readLock().lock();
    try {
      return mtree.isStorageGroup(path);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get series type for given seriesPath.
   *
   * @param path full path
   */
  public TSDataType getSeriesType(String path) throws MetadataException {
    lock.readLock().lock();
    try {
      if (path.equals(SQLConstant.RESERVED_TIME)) {
        return TSDataType.INT64;
      }
      return mtree.getSchema(path).getType();
    } finally {
      lock.readLock().unlock();
    }
  }

  public MeasurementSchema[] getSchemas(String deviceId, String[] measurements)
      throws MetadataException {
    lock.readLock().lock();
    try {
      MNode deviceNode = getNodeByPath(deviceId);
      MeasurementSchema[] measurementSchemas = new MeasurementSchema[measurements.length];
      for (int i = 0; i < measurementSchemas.length; i++) {
        if (!deviceNode.hasChild(measurements[i])) {
          throw new MetadataException(measurements[i] + " does not exist in " + deviceId);
        }
        measurementSchemas[i] = ((LeafMNode) deviceNode.getChild(measurements[i])).getSchema();
      }
      return measurementSchemas;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get all devices under given prefixPath.
   *
   * @param prefixPath a prefix of a full path. if the wildcard is not at the tail, then each
   * wildcard can only match one level, otherwise it can match to the tail.
   * @return A HashSet instance which stores devices names with given prefixPath.
   */
  public Set<String> getDevices(String prefixPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getDevices(prefixPath);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get all nodes from the given level
   *
   * @param prefixPath can be a prefix of a full path. Can not be a full path. can not have
   * wildcard. But, the level of the prefixPath can be smaller than the given level, e.g.,
   * prefixPath = root.a while the given level is 5
   * @param nodeLevel the level can not be smaller than the level of the prefixPath
   * @return A List instance which stores all node at given level
   */
  public List<String> getNodesList(String prefixPath, int nodeLevel) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getNodesList(prefixPath, nodeLevel);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get storage group name by path
   *
   * e.g., root.sg1 is a storage group and path = root.sg1.d1, return root.sg1
   *
   * @return storage group in the given path
   */
  public String getStorageGroupName(String path) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getStorageGroupName(path);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get all storage group names
   */
  public List<String> getAllStorageGroupNames() {
    lock.readLock().lock();
    try {
      return mtree.getAllStorageGroupNames();
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get all storage group MNodes
   */
  public List<StorageGroupMNode> getAllStorageGroupNodes() {
    lock.readLock().lock();
    try {
      return mtree.getAllStorageGroupNodes();
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Return all paths for given path if the path is abstract. Or return the path itself. Regular
   * expression in this method is formed by the amalgamation of seriesPath and the character '*'.
   *
   * @param prefixPath can be a prefix or a full path. if the wildcard is not at the tail, then each
   * wildcard can only match one level, otherwise it can match to the tail.
   */
  public List<String> getAllTimeseriesName(String prefixPath) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getAllTimeseriesName(prefixPath);
    } catch (MetadataException e) {
      throw new MetadataException(e);
    } finally {
      lock.readLock().unlock();
    }
  }

  public List<ShowTimeSeriesResult> getAllTimeseriesSchema(ShowTimeSeriesPlan plan)
      throws MetadataException {
    lock.readLock().lock();
    try {
      if (!tagIndex.containsKey(plan.getKey())) {
        throw new MetadataException("The key " + plan.getKey() + " is not a tag.");
      }
      Map<String, Set<LeafMNode>> value2Node = tagIndex.get(plan.getKey());
      Set<LeafMNode> allMatchedNodes = new TreeSet<>(Comparator.comparing(MNode::getFullPath));
      if (plan.isContains()) {
        for (Entry<String, Set<LeafMNode>> entry : value2Node.entrySet()) {
          String tagValue = entry.getKey();
          if (tagValue.contains(plan.getValue())) {
            allMatchedNodes.addAll(entry.getValue());
          }
        }
      } else {
        for (Entry<String, Set<LeafMNode>> entry : value2Node.entrySet()) {
          String tagValue = entry.getKey();
          if (plan.getValue().equals(tagValue)) {
            allMatchedNodes.addAll(entry.getValue());
          }
        }
      }
      List<ShowTimeSeriesResult> res = new LinkedList<>();
      String[] prefixNodes = MetaUtils.getNodeNames(plan.getPath().getFullPath());
      int curOffset = -1;
      int count = 0;
      int limit = plan.getLimit();
      int offset = plan.getOffset();
      for (LeafMNode leaf : allMatchedNodes) {
        if (match(leaf.getFullPath(), prefixNodes)) {
          if (limit != 0 || offset != 0) {
            curOffset ++;
            if (curOffset < offset || count == limit) {
              continue;
            }
          }
          try {
            Pair<Map<String, String>, Map<String, String>> pair =
                    tagLogFile.read(config.getTagAttributeTotalSize(), leaf.getOffset());
            pair.left.putAll(pair.right);
            MeasurementSchema measurementSchema = leaf.getSchema();
            res.add(new ShowTimeSeriesResult(leaf.getFullPath(), leaf.getAlias(),
                    getStorageGroupName(leaf.getFullPath()), measurementSchema.getType().toString(),
                    measurementSchema.getEncodingType().toString(),
                    measurementSchema.getCompressor().toString(), pair.left));
            if (limit != 0 || offset != 0) {
              count++;
            }
          } catch (IOException e) {
            throw new MetadataException(
                "Something went wrong while deserialize tag info of " + leaf.getFullPath(), e);
          }
        }
      }
      return res;
    }  finally {
      lock.readLock().unlock();
    }
  }

  /**
   * whether the full path has the prefixNodes
   */
  private boolean match(String fullPath, String[] prefixNodes) {
    String[] nodes = MetaUtils.getNodeNames(fullPath);
    if (nodes.length < prefixNodes.length) {
      return false;
    }
    for (int i = 0; i < prefixNodes.length; i++) {
      if (!"*".equals(prefixNodes[i]) && !prefixNodes[i].equals(nodes[i])) {
        return false;
      }
    }
    return true;
  }

  /**
   * Get the result of ShowTimeseriesPlan
   *
   * @param plan show time series query plan
   */
  public List<ShowTimeSeriesResult> showTimeseries(ShowTimeSeriesPlan plan) throws MetadataException {
    lock.readLock().lock();
    try {
      List<String[]> ans = mtree.getAllMeasurementSchema(plan);
      List<ShowTimeSeriesResult> res = new LinkedList<>();
      for (String[] ansString : ans) {
        long tagFileOffset = Long.parseLong(ansString[6]);
        try {
          if (tagFileOffset < 0) {
            // no tags/attributes
            res.add(new ShowTimeSeriesResult(ansString[0], ansString[1], ansString[2],
                ansString[3], ansString[4], ansString[5], Collections.emptyMap()));
          } else {
            // has tags/attributes
            Pair<Map<String, String>, Map<String, String>> pair =
                tagLogFile.read(config.getTagAttributeTotalSize(), tagFileOffset);
            pair.left.putAll(pair.right);
            res.add(new ShowTimeSeriesResult(ansString[0], ansString[1], ansString[2],
                ansString[3], ansString[4], ansString[5], pair.left));
          }
        } catch (IOException e) {
          throw new MetadataException(
              "Something went wrong while deserialize tag info of " + ansString[0], e);
        }
      }
      return res;
    }  finally {
      lock.readLock().unlock();
    }
  }



  public MeasurementSchema getSeriesSchema(String device, String measuremnet) throws MetadataException {
    lock.readLock().lock();
    try {
      InternalMNode node = (InternalMNode) mtree.getNodeByPath(device);
      return ((LeafMNode) node.getChild(measuremnet)).getSchema();
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get child node path in the next level of the given path.
   *
   * e.g., MTree has [root.sg1.d1.s1, root.sg1.d1.s2, root.sg1.d2.s1] given path = root.sg1, return
   * [root.sg1.d1, root.sg1.d2]
   *
   * @return All child nodes' seriesPath(s) of given seriesPath.
   */
  public Set<String> getChildNodePathInNextLevel(String path) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getChildNodePathInNextLevel(path);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Check whether the path exists.
   *
   * @param path a full path or a prefix path
   */
  public boolean isPathExist(String path) {
    lock.readLock().lock();
    try {
      return mtree.isPathExist(path);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get node by path
   */
  public MNode getNodeByPath(String path) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getNodeByPath(path);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Get storage group node by path. If storage group is not set, StorageGroupNotSetException will
   * be thrown
   */
  public StorageGroupMNode getStorageGroupNode(String path) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getStorageGroupNode(path);
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * get device node, if the storage group is not set, create it when autoCreateSchema is true
   *
   * @param path path
   */
  public MNode getDeviceNodeWithAutoCreateStorageGroup(String path, boolean autoCreateSchema,
      int sgLevel) throws MetadataException {
    lock.readLock().lock();
    MNode node = null;
    boolean shouldSetStorageGroup = false;
    try {
      node = mNodeCache.get(path);
    } catch (CacheException e) {
      if (!autoCreateSchema) {
        throw new PathNotExistException(path);
      } else {
        shouldSetStorageGroup = e.getCause() instanceof StorageGroupNotSetException;
      }
    } finally {
      lock.readLock().unlock();
      lock.writeLock().lock();
      try {
        if (autoCreateSchema) {
          if (shouldSetStorageGroup) {
            String storageGroupName = MetaUtils.getStorageGroupNameByLevel(path, sgLevel);
            setStorageGroup(storageGroupName);
          }
          node = mtree.getDeviceNodeWithAutoCreating(path);
        }
      } catch (StorageGroupAlreadySetException e) {
        // ignore set storage group concurrently
        node = mtree.getDeviceNodeWithAutoCreating(path);
      } finally {
        lock.writeLock().unlock();
      }
    }
    return node;
  }

  public MNode getDeviceNodeWithAutoCreateStorageGroup(String path) throws MetadataException {
    return getDeviceNodeWithAutoCreateStorageGroup(path, config.isAutoCreateSchemaEnabled(),
        config.getDefaultStorageGroupLevel());
  }

  /**
   * Get metadata in string
   */
  public String getMetadataInString() {
    lock.readLock().lock();
    try {
      return TIME_SERIES_TREE_HEADER + mtree.toString();
    } finally {
      lock.readLock().unlock();
    }
  }

  @TestOnly
  public void setMaxSeriesNumberAmongStorageGroup(long maxSeriesNumberAmongStorageGroup) {
    this.maxSeriesNumberAmongStorageGroup = maxSeriesNumberAmongStorageGroup;
  }

  public long getMaximalSeriesNumberAmongStorageGroups() {
    return maxSeriesNumberAmongStorageGroup;
  }

  public void setTTL(String storageGroup, long dataTTL) throws MetadataException, IOException {
    lock.writeLock().lock();
    try {
      getStorageGroupNode(storageGroup).setDataTTL(dataTTL);
      if (writeToLog) {
        logWriter.setTTL(storageGroup, dataTTL);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * Check whether the given path contains a storage group
   */
  boolean checkStorageGroupByPath(String path) {
    lock.readLock().lock();
    try {
      return mtree.checkStorageGroupByPath(path);
    } finally {
      lock.readLock().unlock();
    }
  }


  /**
   * Get all storage groups under the given path
   *
   * @return List of String represented all storage group names
   * @apiNote :for cluster
   */
  List<String> getStorageGroupByPath(String path) throws MetadataException {
    lock.readLock().lock();
    try {
      return mtree.getStorageGroupByPath(path);
    } catch (MetadataException e) {
      throw new MetadataException(e);
    } finally {
      lock.readLock().unlock();
    }
  }

  public void collectSeries(MNode startingNode, Collection<MeasurementSchema> timeseriesSchemas) {
    Deque<MNode> nodeDeque = new ArrayDeque<>();
    nodeDeque.addLast(startingNode);
    while (!nodeDeque.isEmpty()) {
      MNode node = nodeDeque.removeFirst();
      if (node instanceof LeafMNode) {
        MeasurementSchema nodeSchema = ((LeafMNode) node).getSchema();
        timeseriesSchemas.add(new MeasurementSchema(node.getFullPath(),
            nodeSchema.getType(), nodeSchema.getEncodingType(), nodeSchema.getCompressor()));
      } else if (!node.getChildren().isEmpty()) {
        nodeDeque.addAll(node.getChildren().values());
      }
    }
  }

  public void collectSeries(String startingPath, List<MeasurementSchema> timeseriesSchemas) {
    MNode mNode;
    try {
      mNode = getNodeByPath(startingPath);
    } catch (MetadataException e) {
      return;
    }
    collectSeries(mNode, timeseriesSchemas);
  }

  /**
   * For a path, infer all storage groups it may belong to.
   * The path can have wildcards.
   *
   * Consider the path into two parts: (1) the sub path which can not contain a storage group name and
   * (2) the sub path which is substring that begin after the storage group name.
   *
   * (1) Suppose the part of the path can not contain a storage group name (e.g.,
   * "root".contains("root.sg") == false), then:
   * If the wildcard is not at the tail, then for each wildcard, only one level will be inferred
   * and the wildcard will be removed.
   * If the wildcard is at the tail, then the inference will go on until the storage groups are found
   * and the wildcard will be kept.
   * (2) Suppose the part of the path is a substring that begin after the storage group name. (e.g.,
   *  For "root.*.sg1.a.*.b.*" and "root.x.sg1" is a storage group, then this part is "a.*.b.*").
   *  For this part, keep what it is.
   *
   * Assuming we have three SGs: root.group1, root.group2, root.area1.group3
   * Eg1:
   *  for input "root.*", returns ("root.group1", "root.group1.*"), ("root.group2", "root.group2.*")
   *  ("root.area1.group3", "root.area1.group3.*")
   * Eg2:
   *  for input "root.*.s1", returns ("root.group1", "root.group1.s1"), ("root.group2", "root.group2.s1")
   *
   * Eg3:
   *  for input "root.area1.*", returns ("root.area1.group3", "root.area1.group3.*")
   *
   * @param path can be a prefix or a full path.
   * @return StorageGroupName-FullPath pairs
   */
  public Map<String, String> determineStorageGroup(String path) throws IllegalPathException {
    lock.readLock().lock();
    try {
      return mtree.determineStorageGroup(path);
    } finally {
      lock.readLock().unlock();
    }
  }
}
