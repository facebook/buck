/**
 * Autogenerated by Thrift Compiler (0.10.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package com.facebook.buck.distributed.thrift;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.10.0)")
public class FetchBuildSlaveFinishedStatsResponse implements org.apache.thrift.TBase<FetchBuildSlaveFinishedStatsResponse, FetchBuildSlaveFinishedStatsResponse._Fields>, java.io.Serializable, Cloneable, Comparable<FetchBuildSlaveFinishedStatsResponse> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("FetchBuildSlaveFinishedStatsResponse");

  private static final org.apache.thrift.protocol.TField BUILD_SLAVE_FINISHED_STATS_FIELD_DESC = new org.apache.thrift.protocol.TField("buildSlaveFinishedStats", org.apache.thrift.protocol.TType.STRING, (short)1);

  private static final org.apache.thrift.scheme.SchemeFactory STANDARD_SCHEME_FACTORY = new FetchBuildSlaveFinishedStatsResponseStandardSchemeFactory();
  private static final org.apache.thrift.scheme.SchemeFactory TUPLE_SCHEME_FACTORY = new FetchBuildSlaveFinishedStatsResponseTupleSchemeFactory();

  public java.nio.ByteBuffer buildSlaveFinishedStats; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    BUILD_SLAVE_FINISHED_STATS((short)1, "buildSlaveFinishedStats");

    private static final java.util.Map<java.lang.String, _Fields> byName = new java.util.HashMap<java.lang.String, _Fields>();

    static {
      for (_Fields field : java.util.EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // BUILD_SLAVE_FINISHED_STATS
          return BUILD_SLAVE_FINISHED_STATS;
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
      if (fields == null) throw new java.lang.IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    public static _Fields findByName(java.lang.String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final java.lang.String _fieldName;

    _Fields(short thriftId, java.lang.String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public java.lang.String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final _Fields optionals[] = {_Fields.BUILD_SLAVE_FINISHED_STATS};
  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.BUILD_SLAVE_FINISHED_STATS, new org.apache.thrift.meta_data.FieldMetaData("buildSlaveFinishedStats", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING        , true)));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(FetchBuildSlaveFinishedStatsResponse.class, metaDataMap);
  }

  public FetchBuildSlaveFinishedStatsResponse() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public FetchBuildSlaveFinishedStatsResponse(FetchBuildSlaveFinishedStatsResponse other) {
    if (other.isSetBuildSlaveFinishedStats()) {
      this.buildSlaveFinishedStats = org.apache.thrift.TBaseHelper.copyBinary(other.buildSlaveFinishedStats);
    }
  }

  public FetchBuildSlaveFinishedStatsResponse deepCopy() {
    return new FetchBuildSlaveFinishedStatsResponse(this);
  }

  @Override
  public void clear() {
    this.buildSlaveFinishedStats = null;
  }

  public byte[] getBuildSlaveFinishedStats() {
    setBuildSlaveFinishedStats(org.apache.thrift.TBaseHelper.rightSize(buildSlaveFinishedStats));
    return buildSlaveFinishedStats == null ? null : buildSlaveFinishedStats.array();
  }

  public java.nio.ByteBuffer bufferForBuildSlaveFinishedStats() {
    return org.apache.thrift.TBaseHelper.copyBinary(buildSlaveFinishedStats);
  }

  public FetchBuildSlaveFinishedStatsResponse setBuildSlaveFinishedStats(byte[] buildSlaveFinishedStats) {
    this.buildSlaveFinishedStats = buildSlaveFinishedStats == null ? (java.nio.ByteBuffer)null : java.nio.ByteBuffer.wrap(buildSlaveFinishedStats.clone());
    return this;
  }

  public FetchBuildSlaveFinishedStatsResponse setBuildSlaveFinishedStats(java.nio.ByteBuffer buildSlaveFinishedStats) {
    this.buildSlaveFinishedStats = org.apache.thrift.TBaseHelper.copyBinary(buildSlaveFinishedStats);
    return this;
  }

  public void unsetBuildSlaveFinishedStats() {
    this.buildSlaveFinishedStats = null;
  }

  /** Returns true if field buildSlaveFinishedStats is set (has been assigned a value) and false otherwise */
  public boolean isSetBuildSlaveFinishedStats() {
    return this.buildSlaveFinishedStats != null;
  }

  public void setBuildSlaveFinishedStatsIsSet(boolean value) {
    if (!value) {
      this.buildSlaveFinishedStats = null;
    }
  }

  public void setFieldValue(_Fields field, java.lang.Object value) {
    switch (field) {
    case BUILD_SLAVE_FINISHED_STATS:
      if (value == null) {
        unsetBuildSlaveFinishedStats();
      } else {
        if (value instanceof byte[]) {
          setBuildSlaveFinishedStats((byte[])value);
        } else {
          setBuildSlaveFinishedStats((java.nio.ByteBuffer)value);
        }
      }
      break;

    }
  }

  public java.lang.Object getFieldValue(_Fields field) {
    switch (field) {
    case BUILD_SLAVE_FINISHED_STATS:
      return getBuildSlaveFinishedStats();

    }
    throw new java.lang.IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new java.lang.IllegalArgumentException();
    }

    switch (field) {
    case BUILD_SLAVE_FINISHED_STATS:
      return isSetBuildSlaveFinishedStats();
    }
    throw new java.lang.IllegalStateException();
  }

  @Override
  public boolean equals(java.lang.Object that) {
    if (that == null)
      return false;
    if (that instanceof FetchBuildSlaveFinishedStatsResponse)
      return this.equals((FetchBuildSlaveFinishedStatsResponse)that);
    return false;
  }

  public boolean equals(FetchBuildSlaveFinishedStatsResponse that) {
    if (that == null)
      return false;
    if (this == that)
      return true;

    boolean this_present_buildSlaveFinishedStats = true && this.isSetBuildSlaveFinishedStats();
    boolean that_present_buildSlaveFinishedStats = true && that.isSetBuildSlaveFinishedStats();
    if (this_present_buildSlaveFinishedStats || that_present_buildSlaveFinishedStats) {
      if (!(this_present_buildSlaveFinishedStats && that_present_buildSlaveFinishedStats))
        return false;
      if (!this.buildSlaveFinishedStats.equals(that.buildSlaveFinishedStats))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int hashCode = 1;

    hashCode = hashCode * 8191 + ((isSetBuildSlaveFinishedStats()) ? 131071 : 524287);
    if (isSetBuildSlaveFinishedStats())
      hashCode = hashCode * 8191 + buildSlaveFinishedStats.hashCode();

    return hashCode;
  }

  @Override
  public int compareTo(FetchBuildSlaveFinishedStatsResponse other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = java.lang.Boolean.valueOf(isSetBuildSlaveFinishedStats()).compareTo(other.isSetBuildSlaveFinishedStats());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetBuildSlaveFinishedStats()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.buildSlaveFinishedStats, other.buildSlaveFinishedStats);
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
    scheme(iprot).read(iprot, this);
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    scheme(oprot).write(oprot, this);
  }

  @Override
  public java.lang.String toString() {
    java.lang.StringBuilder sb = new java.lang.StringBuilder("FetchBuildSlaveFinishedStatsResponse(");
    boolean first = true;

    if (isSetBuildSlaveFinishedStats()) {
      sb.append("buildSlaveFinishedStats:");
      if (this.buildSlaveFinishedStats == null) {
        sb.append("null");
      } else {
        org.apache.thrift.TBaseHelper.toString(this.buildSlaveFinishedStats, sb);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, java.lang.ClassNotFoundException {
    try {
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class FetchBuildSlaveFinishedStatsResponseStandardSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public FetchBuildSlaveFinishedStatsResponseStandardScheme getScheme() {
      return new FetchBuildSlaveFinishedStatsResponseStandardScheme();
    }
  }

  private static class FetchBuildSlaveFinishedStatsResponseStandardScheme extends org.apache.thrift.scheme.StandardScheme<FetchBuildSlaveFinishedStatsResponse> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, FetchBuildSlaveFinishedStatsResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // BUILD_SLAVE_FINISHED_STATS
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.buildSlaveFinishedStats = iprot.readBinary();
              struct.setBuildSlaveFinishedStatsIsSet(true);
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

    public void write(org.apache.thrift.protocol.TProtocol oprot, FetchBuildSlaveFinishedStatsResponse struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.buildSlaveFinishedStats != null) {
        if (struct.isSetBuildSlaveFinishedStats()) {
          oprot.writeFieldBegin(BUILD_SLAVE_FINISHED_STATS_FIELD_DESC);
          oprot.writeBinary(struct.buildSlaveFinishedStats);
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class FetchBuildSlaveFinishedStatsResponseTupleSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public FetchBuildSlaveFinishedStatsResponseTupleScheme getScheme() {
      return new FetchBuildSlaveFinishedStatsResponseTupleScheme();
    }
  }

  private static class FetchBuildSlaveFinishedStatsResponseTupleScheme extends org.apache.thrift.scheme.TupleScheme<FetchBuildSlaveFinishedStatsResponse> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, FetchBuildSlaveFinishedStatsResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol oprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet optionals = new java.util.BitSet();
      if (struct.isSetBuildSlaveFinishedStats()) {
        optionals.set(0);
      }
      oprot.writeBitSet(optionals, 1);
      if (struct.isSetBuildSlaveFinishedStats()) {
        oprot.writeBinary(struct.buildSlaveFinishedStats);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, FetchBuildSlaveFinishedStatsResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol iprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet incoming = iprot.readBitSet(1);
      if (incoming.get(0)) {
        struct.buildSlaveFinishedStats = iprot.readBinary();
        struct.setBuildSlaveFinishedStatsIsSet(true);
      }
    }
  }

  private static <S extends org.apache.thrift.scheme.IScheme> S scheme(org.apache.thrift.protocol.TProtocol proto) {
    return (org.apache.thrift.scheme.StandardScheme.class.equals(proto.getScheme()) ? STANDARD_SCHEME_FACTORY : TUPLE_SCHEME_FACTORY).getScheme();
  }
}

