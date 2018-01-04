/**
 * Autogenerated by Thrift Compiler (0.9.3)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package com.facebook.buck.distributed.thrift;

import org.apache.thrift.scheme.IScheme;
import org.apache.thrift.scheme.SchemeFactory;
import org.apache.thrift.scheme.StandardScheme;

import org.apache.thrift.scheme.TupleScheme;
import org.apache.thrift.protocol.TTupleProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.EncodingUtils;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.server.AbstractNonblockingServer.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.Set;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.Collections;
import java.util.BitSet;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.annotation.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked"})
@Generated(value = "Autogenerated by Thrift Compiler (0.9.3)", date = "2017-12-19")
public class BuildSlaveFinishedStats implements org.apache.thrift.TBase<BuildSlaveFinishedStats, BuildSlaveFinishedStats._Fields>, java.io.Serializable, Cloneable, Comparable<BuildSlaveFinishedStats> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("BuildSlaveFinishedStats");

  private static final org.apache.thrift.protocol.TField BUILD_SLAVE_STATUS_FIELD_DESC = new org.apache.thrift.protocol.TField("buildSlaveStatus", org.apache.thrift.protocol.TType.STRUCT, (short)1);
  private static final org.apache.thrift.protocol.TField EXIT_CODE_FIELD_DESC = new org.apache.thrift.protocol.TField("exitCode", org.apache.thrift.protocol.TType.I32, (short)2);
  private static final org.apache.thrift.protocol.TField FILE_MATERIALIZATION_STATS_FIELD_DESC = new org.apache.thrift.protocol.TField("fileMaterializationStats", org.apache.thrift.protocol.TType.STRUCT, (short)3);
  private static final org.apache.thrift.protocol.TField BUILD_SLAVE_PER_STAGE_TIMING_STATS_FIELD_DESC = new org.apache.thrift.protocol.TField("buildSlavePerStageTimingStats", org.apache.thrift.protocol.TType.STRUCT, (short)4);
  private static final org.apache.thrift.protocol.TField HOSTNAME_FIELD_DESC = new org.apache.thrift.protocol.TField("hostname", org.apache.thrift.protocol.TType.STRING, (short)5);
  private static final org.apache.thrift.protocol.TField DIST_BUILD_MODE_FIELD_DESC = new org.apache.thrift.protocol.TField("distBuildMode", org.apache.thrift.protocol.TType.STRING, (short)6);
  private static final org.apache.thrift.protocol.TField HEALTH_CHECK_STATS_FIELD_DESC = new org.apache.thrift.protocol.TField("healthCheckStats", org.apache.thrift.protocol.TType.STRUCT, (short)7);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new BuildSlaveFinishedStatsStandardSchemeFactory());
    schemes.put(TupleScheme.class, new BuildSlaveFinishedStatsTupleSchemeFactory());
  }

  public BuildSlaveStatus buildSlaveStatus; // optional
  public int exitCode; // optional
  public FileMaterializationStats fileMaterializationStats; // optional
  public BuildSlavePerStageTimingStats buildSlavePerStageTimingStats; // optional
  public String hostname; // optional
  public String distBuildMode; // optional
  public HealthCheckStats healthCheckStats; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    BUILD_SLAVE_STATUS((short)1, "buildSlaveStatus"),
    EXIT_CODE((short)2, "exitCode"),
    FILE_MATERIALIZATION_STATS((short)3, "fileMaterializationStats"),
    BUILD_SLAVE_PER_STAGE_TIMING_STATS((short)4, "buildSlavePerStageTimingStats"),
    HOSTNAME((short)5, "hostname"),
    DIST_BUILD_MODE((short)6, "distBuildMode"),
    HEALTH_CHECK_STATS((short)7, "healthCheckStats");

    private static final Map<String, _Fields> byName = new HashMap<String, _Fields>();

    static {
      for (_Fields field : EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // BUILD_SLAVE_STATUS
          return BUILD_SLAVE_STATUS;
        case 2: // EXIT_CODE
          return EXIT_CODE;
        case 3: // FILE_MATERIALIZATION_STATS
          return FILE_MATERIALIZATION_STATS;
        case 4: // BUILD_SLAVE_PER_STAGE_TIMING_STATS
          return BUILD_SLAVE_PER_STAGE_TIMING_STATS;
        case 5: // HOSTNAME
          return HOSTNAME;
        case 6: // DIST_BUILD_MODE
          return DIST_BUILD_MODE;
        case 7: // HEALTH_CHECK_STATS
          return HEALTH_CHECK_STATS;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    public static _Fields findByName(String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final String _fieldName;

    _Fields(short thriftId, String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final int __EXITCODE_ISSET_ID = 0;
  private byte __isset_bitfield = 0;
  private static final _Fields optionals[] = {_Fields.BUILD_SLAVE_STATUS,_Fields.EXIT_CODE,_Fields.FILE_MATERIALIZATION_STATS,_Fields.BUILD_SLAVE_PER_STAGE_TIMING_STATS,_Fields.HOSTNAME,_Fields.DIST_BUILD_MODE,_Fields.HEALTH_CHECK_STATS};
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.BUILD_SLAVE_STATUS, new org.apache.thrift.meta_data.FieldMetaData("buildSlaveStatus", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, BuildSlaveStatus.class)));
    tmpMap.put(_Fields.EXIT_CODE, new org.apache.thrift.meta_data.FieldMetaData("exitCode", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I32)));
    tmpMap.put(_Fields.FILE_MATERIALIZATION_STATS, new org.apache.thrift.meta_data.FieldMetaData("fileMaterializationStats", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, FileMaterializationStats.class)));
    tmpMap.put(_Fields.BUILD_SLAVE_PER_STAGE_TIMING_STATS, new org.apache.thrift.meta_data.FieldMetaData("buildSlavePerStageTimingStats", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, BuildSlavePerStageTimingStats.class)));
    tmpMap.put(_Fields.HOSTNAME, new org.apache.thrift.meta_data.FieldMetaData("hostname", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.DIST_BUILD_MODE, new org.apache.thrift.meta_data.FieldMetaData("distBuildMode", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.HEALTH_CHECK_STATS, new org.apache.thrift.meta_data.FieldMetaData("healthCheckStats", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, HealthCheckStats.class)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(BuildSlaveFinishedStats.class, metaDataMap);
  }

  public BuildSlaveFinishedStats() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public BuildSlaveFinishedStats(BuildSlaveFinishedStats other) {
    __isset_bitfield = other.__isset_bitfield;
    if (other.isSetBuildSlaveStatus()) {
      this.buildSlaveStatus = new BuildSlaveStatus(other.buildSlaveStatus);
    }
    this.exitCode = other.exitCode;
    if (other.isSetFileMaterializationStats()) {
      this.fileMaterializationStats = new FileMaterializationStats(other.fileMaterializationStats);
    }
    if (other.isSetBuildSlavePerStageTimingStats()) {
      this.buildSlavePerStageTimingStats = new BuildSlavePerStageTimingStats(other.buildSlavePerStageTimingStats);
    }
    if (other.isSetHostname()) {
      this.hostname = other.hostname;
    }
    if (other.isSetDistBuildMode()) {
      this.distBuildMode = other.distBuildMode;
    }
    if (other.isSetHealthCheckStats()) {
      this.healthCheckStats = new HealthCheckStats(other.healthCheckStats);
    }
  }

  public BuildSlaveFinishedStats deepCopy() {
    return new BuildSlaveFinishedStats(this);
  }

  @Override
  public void clear() {
    this.buildSlaveStatus = null;
    setExitCodeIsSet(false);
    this.exitCode = 0;
    this.fileMaterializationStats = null;
    this.buildSlavePerStageTimingStats = null;
    this.hostname = null;
    this.distBuildMode = null;
    this.healthCheckStats = null;
  }

  public BuildSlaveStatus getBuildSlaveStatus() {
    return this.buildSlaveStatus;
  }

  public BuildSlaveFinishedStats setBuildSlaveStatus(BuildSlaveStatus buildSlaveStatus) {
    this.buildSlaveStatus = buildSlaveStatus;
    return this;
  }

  public void unsetBuildSlaveStatus() {
    this.buildSlaveStatus = null;
  }

  /** Returns true if field buildSlaveStatus is set (has been assigned a value) and false otherwise */
  public boolean isSetBuildSlaveStatus() {
    return this.buildSlaveStatus != null;
  }

  public void setBuildSlaveStatusIsSet(boolean value) {
    if (!value) {
      this.buildSlaveStatus = null;
    }
  }

  public int getExitCode() {
    return this.exitCode;
  }

  public BuildSlaveFinishedStats setExitCode(int exitCode) {
    this.exitCode = exitCode;
    setExitCodeIsSet(true);
    return this;
  }

  public void unsetExitCode() {
    __isset_bitfield = EncodingUtils.clearBit(__isset_bitfield, __EXITCODE_ISSET_ID);
  }

  /** Returns true if field exitCode is set (has been assigned a value) and false otherwise */
  public boolean isSetExitCode() {
    return EncodingUtils.testBit(__isset_bitfield, __EXITCODE_ISSET_ID);
  }

  public void setExitCodeIsSet(boolean value) {
    __isset_bitfield = EncodingUtils.setBit(__isset_bitfield, __EXITCODE_ISSET_ID, value);
  }

  public FileMaterializationStats getFileMaterializationStats() {
    return this.fileMaterializationStats;
  }

  public BuildSlaveFinishedStats setFileMaterializationStats(FileMaterializationStats fileMaterializationStats) {
    this.fileMaterializationStats = fileMaterializationStats;
    return this;
  }

  public void unsetFileMaterializationStats() {
    this.fileMaterializationStats = null;
  }

  /** Returns true if field fileMaterializationStats is set (has been assigned a value) and false otherwise */
  public boolean isSetFileMaterializationStats() {
    return this.fileMaterializationStats != null;
  }

  public void setFileMaterializationStatsIsSet(boolean value) {
    if (!value) {
      this.fileMaterializationStats = null;
    }
  }

  public BuildSlavePerStageTimingStats getBuildSlavePerStageTimingStats() {
    return this.buildSlavePerStageTimingStats;
  }

  public BuildSlaveFinishedStats setBuildSlavePerStageTimingStats(BuildSlavePerStageTimingStats buildSlavePerStageTimingStats) {
    this.buildSlavePerStageTimingStats = buildSlavePerStageTimingStats;
    return this;
  }

  public void unsetBuildSlavePerStageTimingStats() {
    this.buildSlavePerStageTimingStats = null;
  }

  /** Returns true if field buildSlavePerStageTimingStats is set (has been assigned a value) and false otherwise */
  public boolean isSetBuildSlavePerStageTimingStats() {
    return this.buildSlavePerStageTimingStats != null;
  }

  public void setBuildSlavePerStageTimingStatsIsSet(boolean value) {
    if (!value) {
      this.buildSlavePerStageTimingStats = null;
    }
  }

  public String getHostname() {
    return this.hostname;
  }

  public BuildSlaveFinishedStats setHostname(String hostname) {
    this.hostname = hostname;
    return this;
  }

  public void unsetHostname() {
    this.hostname = null;
  }

  /** Returns true if field hostname is set (has been assigned a value) and false otherwise */
  public boolean isSetHostname() {
    return this.hostname != null;
  }

  public void setHostnameIsSet(boolean value) {
    if (!value) {
      this.hostname = null;
    }
  }

  public String getDistBuildMode() {
    return this.distBuildMode;
  }

  public BuildSlaveFinishedStats setDistBuildMode(String distBuildMode) {
    this.distBuildMode = distBuildMode;
    return this;
  }

  public void unsetDistBuildMode() {
    this.distBuildMode = null;
  }

  /** Returns true if field distBuildMode is set (has been assigned a value) and false otherwise */
  public boolean isSetDistBuildMode() {
    return this.distBuildMode != null;
  }

  public void setDistBuildModeIsSet(boolean value) {
    if (!value) {
      this.distBuildMode = null;
    }
  }

  public HealthCheckStats getHealthCheckStats() {
    return this.healthCheckStats;
  }

  public BuildSlaveFinishedStats setHealthCheckStats(HealthCheckStats healthCheckStats) {
    this.healthCheckStats = healthCheckStats;
    return this;
  }

  public void unsetHealthCheckStats() {
    this.healthCheckStats = null;
  }

  /** Returns true if field healthCheckStats is set (has been assigned a value) and false otherwise */
  public boolean isSetHealthCheckStats() {
    return this.healthCheckStats != null;
  }

  public void setHealthCheckStatsIsSet(boolean value) {
    if (!value) {
      this.healthCheckStats = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case BUILD_SLAVE_STATUS:
      if (value == null) {
        unsetBuildSlaveStatus();
      } else {
        setBuildSlaveStatus((BuildSlaveStatus)value);
      }
      break;

    case EXIT_CODE:
      if (value == null) {
        unsetExitCode();
      } else {
        setExitCode((Integer)value);
      }
      break;

    case FILE_MATERIALIZATION_STATS:
      if (value == null) {
        unsetFileMaterializationStats();
      } else {
        setFileMaterializationStats((FileMaterializationStats)value);
      }
      break;

    case BUILD_SLAVE_PER_STAGE_TIMING_STATS:
      if (value == null) {
        unsetBuildSlavePerStageTimingStats();
      } else {
        setBuildSlavePerStageTimingStats((BuildSlavePerStageTimingStats)value);
      }
      break;

    case HOSTNAME:
      if (value == null) {
        unsetHostname();
      } else {
        setHostname((String)value);
      }
      break;

    case DIST_BUILD_MODE:
      if (value == null) {
        unsetDistBuildMode();
      } else {
        setDistBuildMode((String)value);
      }
      break;

    case HEALTH_CHECK_STATS:
      if (value == null) {
        unsetHealthCheckStats();
      } else {
        setHealthCheckStats((HealthCheckStats)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case BUILD_SLAVE_STATUS:
      return getBuildSlaveStatus();

    case EXIT_CODE:
      return getExitCode();

    case FILE_MATERIALIZATION_STATS:
      return getFileMaterializationStats();

    case BUILD_SLAVE_PER_STAGE_TIMING_STATS:
      return getBuildSlavePerStageTimingStats();

    case HOSTNAME:
      return getHostname();

    case DIST_BUILD_MODE:
      return getDistBuildMode();

    case HEALTH_CHECK_STATS:
      return getHealthCheckStats();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case BUILD_SLAVE_STATUS:
      return isSetBuildSlaveStatus();
    case EXIT_CODE:
      return isSetExitCode();
    case FILE_MATERIALIZATION_STATS:
      return isSetFileMaterializationStats();
    case BUILD_SLAVE_PER_STAGE_TIMING_STATS:
      return isSetBuildSlavePerStageTimingStats();
    case HOSTNAME:
      return isSetHostname();
    case DIST_BUILD_MODE:
      return isSetDistBuildMode();
    case HEALTH_CHECK_STATS:
      return isSetHealthCheckStats();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof BuildSlaveFinishedStats)
      return this.equals((BuildSlaveFinishedStats)that);
    return false;
  }

  public boolean equals(BuildSlaveFinishedStats that) {
    if (that == null)
      return false;

    boolean this_present_buildSlaveStatus = true && this.isSetBuildSlaveStatus();
    boolean that_present_buildSlaveStatus = true && that.isSetBuildSlaveStatus();
    if (this_present_buildSlaveStatus || that_present_buildSlaveStatus) {
      if (!(this_present_buildSlaveStatus && that_present_buildSlaveStatus))
        return false;
      if (!this.buildSlaveStatus.equals(that.buildSlaveStatus))
        return false;
    }

    boolean this_present_exitCode = true && this.isSetExitCode();
    boolean that_present_exitCode = true && that.isSetExitCode();
    if (this_present_exitCode || that_present_exitCode) {
      if (!(this_present_exitCode && that_present_exitCode))
        return false;
      if (this.exitCode != that.exitCode)
        return false;
    }

    boolean this_present_fileMaterializationStats = true && this.isSetFileMaterializationStats();
    boolean that_present_fileMaterializationStats = true && that.isSetFileMaterializationStats();
    if (this_present_fileMaterializationStats || that_present_fileMaterializationStats) {
      if (!(this_present_fileMaterializationStats && that_present_fileMaterializationStats))
        return false;
      if (!this.fileMaterializationStats.equals(that.fileMaterializationStats))
        return false;
    }

    boolean this_present_buildSlavePerStageTimingStats = true && this.isSetBuildSlavePerStageTimingStats();
    boolean that_present_buildSlavePerStageTimingStats = true && that.isSetBuildSlavePerStageTimingStats();
    if (this_present_buildSlavePerStageTimingStats || that_present_buildSlavePerStageTimingStats) {
      if (!(this_present_buildSlavePerStageTimingStats && that_present_buildSlavePerStageTimingStats))
        return false;
      if (!this.buildSlavePerStageTimingStats.equals(that.buildSlavePerStageTimingStats))
        return false;
    }

    boolean this_present_hostname = true && this.isSetHostname();
    boolean that_present_hostname = true && that.isSetHostname();
    if (this_present_hostname || that_present_hostname) {
      if (!(this_present_hostname && that_present_hostname))
        return false;
      if (!this.hostname.equals(that.hostname))
        return false;
    }

    boolean this_present_distBuildMode = true && this.isSetDistBuildMode();
    boolean that_present_distBuildMode = true && that.isSetDistBuildMode();
    if (this_present_distBuildMode || that_present_distBuildMode) {
      if (!(this_present_distBuildMode && that_present_distBuildMode))
        return false;
      if (!this.distBuildMode.equals(that.distBuildMode))
        return false;
    }

    boolean this_present_healthCheckStats = true && this.isSetHealthCheckStats();
    boolean that_present_healthCheckStats = true && that.isSetHealthCheckStats();
    if (this_present_healthCheckStats || that_present_healthCheckStats) {
      if (!(this_present_healthCheckStats && that_present_healthCheckStats))
        return false;
      if (!this.healthCheckStats.equals(that.healthCheckStats))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_buildSlaveStatus = true && (isSetBuildSlaveStatus());
    list.add(present_buildSlaveStatus);
    if (present_buildSlaveStatus)
      list.add(buildSlaveStatus);

    boolean present_exitCode = true && (isSetExitCode());
    list.add(present_exitCode);
    if (present_exitCode)
      list.add(exitCode);

    boolean present_fileMaterializationStats = true && (isSetFileMaterializationStats());
    list.add(present_fileMaterializationStats);
    if (present_fileMaterializationStats)
      list.add(fileMaterializationStats);

    boolean present_buildSlavePerStageTimingStats = true && (isSetBuildSlavePerStageTimingStats());
    list.add(present_buildSlavePerStageTimingStats);
    if (present_buildSlavePerStageTimingStats)
      list.add(buildSlavePerStageTimingStats);

    boolean present_hostname = true && (isSetHostname());
    list.add(present_hostname);
    if (present_hostname)
      list.add(hostname);

    boolean present_distBuildMode = true && (isSetDistBuildMode());
    list.add(present_distBuildMode);
    if (present_distBuildMode)
      list.add(distBuildMode);

    boolean present_healthCheckStats = true && (isSetHealthCheckStats());
    list.add(present_healthCheckStats);
    if (present_healthCheckStats)
      list.add(healthCheckStats);

    return list.hashCode();
  }

  @Override
  public int compareTo(BuildSlaveFinishedStats other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(isSetBuildSlaveStatus()).compareTo(other.isSetBuildSlaveStatus());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetBuildSlaveStatus()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.buildSlaveStatus, other.buildSlaveStatus);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetExitCode()).compareTo(other.isSetExitCode());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetExitCode()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.exitCode, other.exitCode);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetFileMaterializationStats()).compareTo(other.isSetFileMaterializationStats());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetFileMaterializationStats()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.fileMaterializationStats, other.fileMaterializationStats);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetBuildSlavePerStageTimingStats()).compareTo(other.isSetBuildSlavePerStageTimingStats());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetBuildSlavePerStageTimingStats()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.buildSlavePerStageTimingStats, other.buildSlavePerStageTimingStats);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetHostname()).compareTo(other.isSetHostname());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetHostname()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.hostname, other.hostname);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetDistBuildMode()).compareTo(other.isSetDistBuildMode());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetDistBuildMode()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.distBuildMode, other.distBuildMode);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetHealthCheckStats()).compareTo(other.isSetHealthCheckStats());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetHealthCheckStats()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.healthCheckStats, other.healthCheckStats);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    schemes.get(iprot.getScheme()).getScheme().read(iprot, this);
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    schemes.get(oprot.getScheme()).getScheme().write(oprot, this);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("BuildSlaveFinishedStats(");
    boolean first = true;

    if (isSetBuildSlaveStatus()) {
      sb.append("buildSlaveStatus:");
      if (this.buildSlaveStatus == null) {
        sb.append("null");
      } else {
        sb.append(this.buildSlaveStatus);
      }
      first = false;
    }
    if (isSetExitCode()) {
      if (!first) sb.append(", ");
      sb.append("exitCode:");
      sb.append(this.exitCode);
      first = false;
    }
    if (isSetFileMaterializationStats()) {
      if (!first) sb.append(", ");
      sb.append("fileMaterializationStats:");
      if (this.fileMaterializationStats == null) {
        sb.append("null");
      } else {
        sb.append(this.fileMaterializationStats);
      }
      first = false;
    }
    if (isSetBuildSlavePerStageTimingStats()) {
      if (!first) sb.append(", ");
      sb.append("buildSlavePerStageTimingStats:");
      if (this.buildSlavePerStageTimingStats == null) {
        sb.append("null");
      } else {
        sb.append(this.buildSlavePerStageTimingStats);
      }
      first = false;
    }
    if (isSetHostname()) {
      if (!first) sb.append(", ");
      sb.append("hostname:");
      if (this.hostname == null) {
        sb.append("null");
      } else {
        sb.append(this.hostname);
      }
      first = false;
    }
    if (isSetDistBuildMode()) {
      if (!first) sb.append(", ");
      sb.append("distBuildMode:");
      if (this.distBuildMode == null) {
        sb.append("null");
      } else {
        sb.append(this.distBuildMode);
      }
      first = false;
    }
    if (isSetHealthCheckStats()) {
      if (!first) sb.append(", ");
      sb.append("healthCheckStats:");
      if (this.healthCheckStats == null) {
        sb.append("null");
      } else {
        sb.append(this.healthCheckStats);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
    if (buildSlaveStatus != null) {
      buildSlaveStatus.validate();
    }
    if (fileMaterializationStats != null) {
      fileMaterializationStats.validate();
    }
    if (buildSlavePerStageTimingStats != null) {
      buildSlavePerStageTimingStats.validate();
    }
    if (healthCheckStats != null) {
      healthCheckStats.validate();
    }
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
    try {
      // it doesn't seem like you should have to do this, but java serialization is wacky, and doesn't call the default constructor.
      __isset_bitfield = 0;
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class BuildSlaveFinishedStatsStandardSchemeFactory implements SchemeFactory {
    public BuildSlaveFinishedStatsStandardScheme getScheme() {
      return new BuildSlaveFinishedStatsStandardScheme();
    }
  }

  private static class BuildSlaveFinishedStatsStandardScheme extends StandardScheme<BuildSlaveFinishedStats> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, BuildSlaveFinishedStats struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // BUILD_SLAVE_STATUS
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.buildSlaveStatus = new BuildSlaveStatus();
              struct.buildSlaveStatus.read(iprot);
              struct.setBuildSlaveStatusIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // EXIT_CODE
            if (schemeField.type == org.apache.thrift.protocol.TType.I32) {
              struct.exitCode = iprot.readI32();
              struct.setExitCodeIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // FILE_MATERIALIZATION_STATS
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.fileMaterializationStats = new FileMaterializationStats();
              struct.fileMaterializationStats.read(iprot);
              struct.setFileMaterializationStatsIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 4: // BUILD_SLAVE_PER_STAGE_TIMING_STATS
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.buildSlavePerStageTimingStats = new BuildSlavePerStageTimingStats();
              struct.buildSlavePerStageTimingStats.read(iprot);
              struct.setBuildSlavePerStageTimingStatsIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 5: // HOSTNAME
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.hostname = iprot.readString();
              struct.setHostnameIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 6: // DIST_BUILD_MODE
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.distBuildMode = iprot.readString();
              struct.setDistBuildModeIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 7: // HEALTH_CHECK_STATS
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.healthCheckStats = new HealthCheckStats();
              struct.healthCheckStats.read(iprot);
              struct.setHealthCheckStatsIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          default:
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
        }
        iprot.readFieldEnd();
      }
      iprot.readStructEnd();

      // check for required fields of primitive type, which can't be checked in the validate method
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, BuildSlaveFinishedStats struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.buildSlaveStatus != null) {
        if (struct.isSetBuildSlaveStatus()) {
          oprot.writeFieldBegin(BUILD_SLAVE_STATUS_FIELD_DESC);
          struct.buildSlaveStatus.write(oprot);
          oprot.writeFieldEnd();
        }
      }
      if (struct.isSetExitCode()) {
        oprot.writeFieldBegin(EXIT_CODE_FIELD_DESC);
        oprot.writeI32(struct.exitCode);
        oprot.writeFieldEnd();
      }
      if (struct.fileMaterializationStats != null) {
        if (struct.isSetFileMaterializationStats()) {
          oprot.writeFieldBegin(FILE_MATERIALIZATION_STATS_FIELD_DESC);
          struct.fileMaterializationStats.write(oprot);
          oprot.writeFieldEnd();
        }
      }
      if (struct.buildSlavePerStageTimingStats != null) {
        if (struct.isSetBuildSlavePerStageTimingStats()) {
          oprot.writeFieldBegin(BUILD_SLAVE_PER_STAGE_TIMING_STATS_FIELD_DESC);
          struct.buildSlavePerStageTimingStats.write(oprot);
          oprot.writeFieldEnd();
        }
      }
      if (struct.hostname != null) {
        if (struct.isSetHostname()) {
          oprot.writeFieldBegin(HOSTNAME_FIELD_DESC);
          oprot.writeString(struct.hostname);
          oprot.writeFieldEnd();
        }
      }
      if (struct.distBuildMode != null) {
        if (struct.isSetDistBuildMode()) {
          oprot.writeFieldBegin(DIST_BUILD_MODE_FIELD_DESC);
          oprot.writeString(struct.distBuildMode);
          oprot.writeFieldEnd();
        }
      }
      if (struct.healthCheckStats != null) {
        if (struct.isSetHealthCheckStats()) {
          oprot.writeFieldBegin(HEALTH_CHECK_STATS_FIELD_DESC);
          struct.healthCheckStats.write(oprot);
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class BuildSlaveFinishedStatsTupleSchemeFactory implements SchemeFactory {
    public BuildSlaveFinishedStatsTupleScheme getScheme() {
      return new BuildSlaveFinishedStatsTupleScheme();
    }
  }

  private static class BuildSlaveFinishedStatsTupleScheme extends TupleScheme<BuildSlaveFinishedStats> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, BuildSlaveFinishedStats struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      BitSet optionals = new BitSet();
      if (struct.isSetBuildSlaveStatus()) {
        optionals.set(0);
      }
      if (struct.isSetExitCode()) {
        optionals.set(1);
      }
      if (struct.isSetFileMaterializationStats()) {
        optionals.set(2);
      }
      if (struct.isSetBuildSlavePerStageTimingStats()) {
        optionals.set(3);
      }
      if (struct.isSetHostname()) {
        optionals.set(4);
      }
      if (struct.isSetDistBuildMode()) {
        optionals.set(5);
      }
      if (struct.isSetHealthCheckStats()) {
        optionals.set(6);
      }
      oprot.writeBitSet(optionals, 7);
      if (struct.isSetBuildSlaveStatus()) {
        struct.buildSlaveStatus.write(oprot);
      }
      if (struct.isSetExitCode()) {
        oprot.writeI32(struct.exitCode);
      }
      if (struct.isSetFileMaterializationStats()) {
        struct.fileMaterializationStats.write(oprot);
      }
      if (struct.isSetBuildSlavePerStageTimingStats()) {
        struct.buildSlavePerStageTimingStats.write(oprot);
      }
      if (struct.isSetHostname()) {
        oprot.writeString(struct.hostname);
      }
      if (struct.isSetDistBuildMode()) {
        oprot.writeString(struct.distBuildMode);
      }
      if (struct.isSetHealthCheckStats()) {
        struct.healthCheckStats.write(oprot);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, BuildSlaveFinishedStats struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      BitSet incoming = iprot.readBitSet(7);
      if (incoming.get(0)) {
        struct.buildSlaveStatus = new BuildSlaveStatus();
        struct.buildSlaveStatus.read(iprot);
        struct.setBuildSlaveStatusIsSet(true);
      }
      if (incoming.get(1)) {
        struct.exitCode = iprot.readI32();
        struct.setExitCodeIsSet(true);
      }
      if (incoming.get(2)) {
        struct.fileMaterializationStats = new FileMaterializationStats();
        struct.fileMaterializationStats.read(iprot);
        struct.setFileMaterializationStatsIsSet(true);
      }
      if (incoming.get(3)) {
        struct.buildSlavePerStageTimingStats = new BuildSlavePerStageTimingStats();
        struct.buildSlavePerStageTimingStats.read(iprot);
        struct.setBuildSlavePerStageTimingStatsIsSet(true);
      }
      if (incoming.get(4)) {
        struct.hostname = iprot.readString();
        struct.setHostnameIsSet(true);
      }
      if (incoming.get(5)) {
        struct.distBuildMode = iprot.readString();
        struct.setDistBuildModeIsSet(true);
      }
      if (incoming.get(6)) {
        struct.healthCheckStats = new HealthCheckStats();
        struct.healthCheckStats.read(iprot);
        struct.setHealthCheckStatsIsSet(true);
      }
    }
  }

}

