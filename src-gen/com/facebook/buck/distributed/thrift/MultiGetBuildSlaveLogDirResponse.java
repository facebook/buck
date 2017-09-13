/**
 * Autogenerated by Thrift Compiler (0.10.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package com.facebook.buck.distributed.thrift;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.10.0)", date = "2017-09-01")
public class MultiGetBuildSlaveLogDirResponse implements org.apache.thrift.TBase<MultiGetBuildSlaveLogDirResponse, MultiGetBuildSlaveLogDirResponse._Fields>, java.io.Serializable, Cloneable, Comparable<MultiGetBuildSlaveLogDirResponse> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("MultiGetBuildSlaveLogDirResponse");

  private static final org.apache.thrift.protocol.TField LOG_DIRS_FIELD_DESC = new org.apache.thrift.protocol.TField("logDirs", org.apache.thrift.protocol.TType.LIST, (short)1);

  private static final org.apache.thrift.scheme.SchemeFactory STANDARD_SCHEME_FACTORY = new MultiGetBuildSlaveLogDirResponseStandardSchemeFactory();
  private static final org.apache.thrift.scheme.SchemeFactory TUPLE_SCHEME_FACTORY = new MultiGetBuildSlaveLogDirResponseTupleSchemeFactory();

  public java.util.List<LogDir> logDirs; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    LOG_DIRS((short)1, "logDirs");

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
        case 1: // LOG_DIRS
          return LOG_DIRS;
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
  private static final _Fields optionals[] = {_Fields.LOG_DIRS};
  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.LOG_DIRS, new org.apache.thrift.meta_data.FieldMetaData("logDirs", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.ListMetaData(org.apache.thrift.protocol.TType.LIST, 
            new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, LogDir.class))));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(MultiGetBuildSlaveLogDirResponse.class, metaDataMap);
  }

  public MultiGetBuildSlaveLogDirResponse() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public MultiGetBuildSlaveLogDirResponse(MultiGetBuildSlaveLogDirResponse other) {
    if (other.isSetLogDirs()) {
      java.util.List<LogDir> __this__logDirs = new java.util.ArrayList<LogDir>(other.logDirs.size());
      for (LogDir other_element : other.logDirs) {
        __this__logDirs.add(new LogDir(other_element));
      }
      this.logDirs = __this__logDirs;
    }
  }

  public MultiGetBuildSlaveLogDirResponse deepCopy() {
    return new MultiGetBuildSlaveLogDirResponse(this);
  }

  @Override
  public void clear() {
    this.logDirs = null;
  }

  public int getLogDirsSize() {
    return (this.logDirs == null) ? 0 : this.logDirs.size();
  }

  public java.util.Iterator<LogDir> getLogDirsIterator() {
    return (this.logDirs == null) ? null : this.logDirs.iterator();
  }

  public void addToLogDirs(LogDir elem) {
    if (this.logDirs == null) {
      this.logDirs = new java.util.ArrayList<LogDir>();
    }
    this.logDirs.add(elem);
  }

  public java.util.List<LogDir> getLogDirs() {
    return this.logDirs;
  }

  public MultiGetBuildSlaveLogDirResponse setLogDirs(java.util.List<LogDir> logDirs) {
    this.logDirs = logDirs;
    return this;
  }

  public void unsetLogDirs() {
    this.logDirs = null;
  }

  /** Returns true if field logDirs is set (has been assigned a value) and false otherwise */
  public boolean isSetLogDirs() {
    return this.logDirs != null;
  }

  public void setLogDirsIsSet(boolean value) {
    if (!value) {
      this.logDirs = null;
    }
  }

  public void setFieldValue(_Fields field, java.lang.Object value) {
    switch (field) {
    case LOG_DIRS:
      if (value == null) {
        unsetLogDirs();
      } else {
        setLogDirs((java.util.List<LogDir>)value);
      }
      break;

    }
  }

  public java.lang.Object getFieldValue(_Fields field) {
    switch (field) {
    case LOG_DIRS:
      return getLogDirs();

    }
    throw new java.lang.IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new java.lang.IllegalArgumentException();
    }

    switch (field) {
    case LOG_DIRS:
      return isSetLogDirs();
    }
    throw new java.lang.IllegalStateException();
  }

  @Override
  public boolean equals(java.lang.Object that) {
    if (that == null)
      return false;
    if (that instanceof MultiGetBuildSlaveLogDirResponse)
      return this.equals((MultiGetBuildSlaveLogDirResponse)that);
    return false;
  }

  public boolean equals(MultiGetBuildSlaveLogDirResponse that) {
    if (that == null)
      return false;
    if (this == that)
      return true;

    boolean this_present_logDirs = true && this.isSetLogDirs();
    boolean that_present_logDirs = true && that.isSetLogDirs();
    if (this_present_logDirs || that_present_logDirs) {
      if (!(this_present_logDirs && that_present_logDirs))
        return false;
      if (!this.logDirs.equals(that.logDirs))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int hashCode = 1;

    hashCode = hashCode * 8191 + ((isSetLogDirs()) ? 131071 : 524287);
    if (isSetLogDirs())
      hashCode = hashCode * 8191 + logDirs.hashCode();

    return hashCode;
  }

  @Override
  public int compareTo(MultiGetBuildSlaveLogDirResponse other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = java.lang.Boolean.valueOf(isSetLogDirs()).compareTo(other.isSetLogDirs());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetLogDirs()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.logDirs, other.logDirs);
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
    java.lang.StringBuilder sb = new java.lang.StringBuilder("MultiGetBuildSlaveLogDirResponse(");
    boolean first = true;

    if (isSetLogDirs()) {
      sb.append("logDirs:");
      if (this.logDirs == null) {
        sb.append("null");
      } else {
        sb.append(this.logDirs);
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

  private static class MultiGetBuildSlaveLogDirResponseStandardSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public MultiGetBuildSlaveLogDirResponseStandardScheme getScheme() {
      return new MultiGetBuildSlaveLogDirResponseStandardScheme();
    }
  }

  private static class MultiGetBuildSlaveLogDirResponseStandardScheme extends org.apache.thrift.scheme.StandardScheme<MultiGetBuildSlaveLogDirResponse> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, MultiGetBuildSlaveLogDirResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // LOG_DIRS
            if (schemeField.type == org.apache.thrift.protocol.TType.LIST) {
              {
                org.apache.thrift.protocol.TList _list114 = iprot.readListBegin();
                struct.logDirs = new java.util.ArrayList<LogDir>(_list114.size);
                LogDir _elem115;
                for (int _i116 = 0; _i116 < _list114.size; ++_i116)
                {
                  _elem115 = new LogDir();
                  _elem115.read(iprot);
                  struct.logDirs.add(_elem115);
                }
                iprot.readListEnd();
              }
              struct.setLogDirsIsSet(true);
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

    public void write(org.apache.thrift.protocol.TProtocol oprot, MultiGetBuildSlaveLogDirResponse struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.logDirs != null) {
        if (struct.isSetLogDirs()) {
          oprot.writeFieldBegin(LOG_DIRS_FIELD_DESC);
          {
            oprot.writeListBegin(new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRUCT, struct.logDirs.size()));
            for (LogDir _iter117 : struct.logDirs)
            {
              _iter117.write(oprot);
            }
            oprot.writeListEnd();
          }
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class MultiGetBuildSlaveLogDirResponseTupleSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public MultiGetBuildSlaveLogDirResponseTupleScheme getScheme() {
      return new MultiGetBuildSlaveLogDirResponseTupleScheme();
    }
  }

  private static class MultiGetBuildSlaveLogDirResponseTupleScheme extends org.apache.thrift.scheme.TupleScheme<MultiGetBuildSlaveLogDirResponse> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, MultiGetBuildSlaveLogDirResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol oprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet optionals = new java.util.BitSet();
      if (struct.isSetLogDirs()) {
        optionals.set(0);
      }
      oprot.writeBitSet(optionals, 1);
      if (struct.isSetLogDirs()) {
        {
          oprot.writeI32(struct.logDirs.size());
          for (LogDir _iter118 : struct.logDirs)
          {
            _iter118.write(oprot);
          }
        }
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, MultiGetBuildSlaveLogDirResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol iprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet incoming = iprot.readBitSet(1);
      if (incoming.get(0)) {
        {
          org.apache.thrift.protocol.TList _list119 = new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRUCT, iprot.readI32());
          struct.logDirs = new java.util.ArrayList<LogDir>(_list119.size);
          LogDir _elem120;
          for (int _i121 = 0; _i121 < _list119.size; ++_i121)
          {
            _elem120 = new LogDir();
            _elem120.read(iprot);
            struct.logDirs.add(_elem120);
          }
        }
        struct.setLogDirsIsSet(true);
      }
    }
  }

  private static <S extends org.apache.thrift.scheme.IScheme> S scheme(org.apache.thrift.protocol.TProtocol proto) {
    return (org.apache.thrift.scheme.StandardScheme.class.equals(proto.getScheme()) ? STANDARD_SCHEME_FACTORY : TUPLE_SCHEME_FACTORY).getScheme();
  }
}

