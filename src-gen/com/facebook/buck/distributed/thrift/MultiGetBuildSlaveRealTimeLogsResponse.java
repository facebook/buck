/**
 * Autogenerated by Thrift Compiler (0.10.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package com.facebook.buck.distributed.thrift;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.10.0)")
public class MultiGetBuildSlaveRealTimeLogsResponse implements org.apache.thrift.TBase<MultiGetBuildSlaveRealTimeLogsResponse, MultiGetBuildSlaveRealTimeLogsResponse._Fields>, java.io.Serializable, Cloneable, Comparable<MultiGetBuildSlaveRealTimeLogsResponse> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("MultiGetBuildSlaveRealTimeLogsResponse");

  private static final org.apache.thrift.protocol.TField MULTI_STREAM_LOGS_FIELD_DESC = new org.apache.thrift.protocol.TField("multiStreamLogs", org.apache.thrift.protocol.TType.LIST, (short)1);

  private static final org.apache.thrift.scheme.SchemeFactory STANDARD_SCHEME_FACTORY = new MultiGetBuildSlaveRealTimeLogsResponseStandardSchemeFactory();
  private static final org.apache.thrift.scheme.SchemeFactory TUPLE_SCHEME_FACTORY = new MultiGetBuildSlaveRealTimeLogsResponseTupleSchemeFactory();

  public java.util.List<StreamLogs> multiStreamLogs; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    MULTI_STREAM_LOGS((short)1, "multiStreamLogs");

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
        case 1: // MULTI_STREAM_LOGS
          return MULTI_STREAM_LOGS;
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
  private static final _Fields optionals[] = {_Fields.MULTI_STREAM_LOGS};
  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.MULTI_STREAM_LOGS, new org.apache.thrift.meta_data.FieldMetaData("multiStreamLogs", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.ListMetaData(org.apache.thrift.protocol.TType.LIST, 
            new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, StreamLogs.class))));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(MultiGetBuildSlaveRealTimeLogsResponse.class, metaDataMap);
  }

  public MultiGetBuildSlaveRealTimeLogsResponse() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public MultiGetBuildSlaveRealTimeLogsResponse(MultiGetBuildSlaveRealTimeLogsResponse other) {
    if (other.isSetMultiStreamLogs()) {
      java.util.List<StreamLogs> __this__multiStreamLogs = new java.util.ArrayList<StreamLogs>(other.multiStreamLogs.size());
      for (StreamLogs other_element : other.multiStreamLogs) {
        __this__multiStreamLogs.add(new StreamLogs(other_element));
      }
      this.multiStreamLogs = __this__multiStreamLogs;
    }
  }

  public MultiGetBuildSlaveRealTimeLogsResponse deepCopy() {
    return new MultiGetBuildSlaveRealTimeLogsResponse(this);
  }

  @Override
  public void clear() {
    this.multiStreamLogs = null;
  }

  public int getMultiStreamLogsSize() {
    return (this.multiStreamLogs == null) ? 0 : this.multiStreamLogs.size();
  }

  public java.util.Iterator<StreamLogs> getMultiStreamLogsIterator() {
    return (this.multiStreamLogs == null) ? null : this.multiStreamLogs.iterator();
  }

  public void addToMultiStreamLogs(StreamLogs elem) {
    if (this.multiStreamLogs == null) {
      this.multiStreamLogs = new java.util.ArrayList<StreamLogs>();
    }
    this.multiStreamLogs.add(elem);
  }

  public java.util.List<StreamLogs> getMultiStreamLogs() {
    return this.multiStreamLogs;
  }

  public MultiGetBuildSlaveRealTimeLogsResponse setMultiStreamLogs(java.util.List<StreamLogs> multiStreamLogs) {
    this.multiStreamLogs = multiStreamLogs;
    return this;
  }

  public void unsetMultiStreamLogs() {
    this.multiStreamLogs = null;
  }

  /** Returns true if field multiStreamLogs is set (has been assigned a value) and false otherwise */
  public boolean isSetMultiStreamLogs() {
    return this.multiStreamLogs != null;
  }

  public void setMultiStreamLogsIsSet(boolean value) {
    if (!value) {
      this.multiStreamLogs = null;
    }
  }

  public void setFieldValue(_Fields field, java.lang.Object value) {
    switch (field) {
    case MULTI_STREAM_LOGS:
      if (value == null) {
        unsetMultiStreamLogs();
      } else {
        setMultiStreamLogs((java.util.List<StreamLogs>)value);
      }
      break;

    }
  }

  public java.lang.Object getFieldValue(_Fields field) {
    switch (field) {
    case MULTI_STREAM_LOGS:
      return getMultiStreamLogs();

    }
    throw new java.lang.IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new java.lang.IllegalArgumentException();
    }

    switch (field) {
    case MULTI_STREAM_LOGS:
      return isSetMultiStreamLogs();
    }
    throw new java.lang.IllegalStateException();
  }

  @Override
  public boolean equals(java.lang.Object that) {
    if (that == null)
      return false;
    if (that instanceof MultiGetBuildSlaveRealTimeLogsResponse)
      return this.equals((MultiGetBuildSlaveRealTimeLogsResponse)that);
    return false;
  }

  public boolean equals(MultiGetBuildSlaveRealTimeLogsResponse that) {
    if (that == null)
      return false;
    if (this == that)
      return true;

    boolean this_present_multiStreamLogs = true && this.isSetMultiStreamLogs();
    boolean that_present_multiStreamLogs = true && that.isSetMultiStreamLogs();
    if (this_present_multiStreamLogs || that_present_multiStreamLogs) {
      if (!(this_present_multiStreamLogs && that_present_multiStreamLogs))
        return false;
      if (!this.multiStreamLogs.equals(that.multiStreamLogs))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int hashCode = 1;

    hashCode = hashCode * 8191 + ((isSetMultiStreamLogs()) ? 131071 : 524287);
    if (isSetMultiStreamLogs())
      hashCode = hashCode * 8191 + multiStreamLogs.hashCode();

    return hashCode;
  }

  @Override
  public int compareTo(MultiGetBuildSlaveRealTimeLogsResponse other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = java.lang.Boolean.valueOf(isSetMultiStreamLogs()).compareTo(other.isSetMultiStreamLogs());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetMultiStreamLogs()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.multiStreamLogs, other.multiStreamLogs);
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
    java.lang.StringBuilder sb = new java.lang.StringBuilder("MultiGetBuildSlaveRealTimeLogsResponse(");
    boolean first = true;

    if (isSetMultiStreamLogs()) {
      sb.append("multiStreamLogs:");
      if (this.multiStreamLogs == null) {
        sb.append("null");
      } else {
        sb.append(this.multiStreamLogs);
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

  private static class MultiGetBuildSlaveRealTimeLogsResponseStandardSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public MultiGetBuildSlaveRealTimeLogsResponseStandardScheme getScheme() {
      return new MultiGetBuildSlaveRealTimeLogsResponseStandardScheme();
    }
  }

  private static class MultiGetBuildSlaveRealTimeLogsResponseStandardScheme extends org.apache.thrift.scheme.StandardScheme<MultiGetBuildSlaveRealTimeLogsResponse> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, MultiGetBuildSlaveRealTimeLogsResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // MULTI_STREAM_LOGS
            if (schemeField.type == org.apache.thrift.protocol.TType.LIST) {
              {
                org.apache.thrift.protocol.TList _list128 = iprot.readListBegin();
                struct.multiStreamLogs = new java.util.ArrayList<StreamLogs>(_list128.size);
                StreamLogs _elem129;
                for (int _i130 = 0; _i130 < _list128.size; ++_i130)
                {
                  _elem129 = new StreamLogs();
                  _elem129.read(iprot);
                  struct.multiStreamLogs.add(_elem129);
                }
                iprot.readListEnd();
              }
              struct.setMultiStreamLogsIsSet(true);
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

    public void write(org.apache.thrift.protocol.TProtocol oprot, MultiGetBuildSlaveRealTimeLogsResponse struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.multiStreamLogs != null) {
        if (struct.isSetMultiStreamLogs()) {
          oprot.writeFieldBegin(MULTI_STREAM_LOGS_FIELD_DESC);
          {
            oprot.writeListBegin(new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRUCT, struct.multiStreamLogs.size()));
            for (StreamLogs _iter131 : struct.multiStreamLogs)
            {
              _iter131.write(oprot);
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

  private static class MultiGetBuildSlaveRealTimeLogsResponseTupleSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public MultiGetBuildSlaveRealTimeLogsResponseTupleScheme getScheme() {
      return new MultiGetBuildSlaveRealTimeLogsResponseTupleScheme();
    }
  }

  private static class MultiGetBuildSlaveRealTimeLogsResponseTupleScheme extends org.apache.thrift.scheme.TupleScheme<MultiGetBuildSlaveRealTimeLogsResponse> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, MultiGetBuildSlaveRealTimeLogsResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol oprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet optionals = new java.util.BitSet();
      if (struct.isSetMultiStreamLogs()) {
        optionals.set(0);
      }
      oprot.writeBitSet(optionals, 1);
      if (struct.isSetMultiStreamLogs()) {
        {
          oprot.writeI32(struct.multiStreamLogs.size());
          for (StreamLogs _iter132 : struct.multiStreamLogs)
          {
            _iter132.write(oprot);
          }
        }
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, MultiGetBuildSlaveRealTimeLogsResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol iprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet incoming = iprot.readBitSet(1);
      if (incoming.get(0)) {
        {
          org.apache.thrift.protocol.TList _list133 = new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRUCT, iprot.readI32());
          struct.multiStreamLogs = new java.util.ArrayList<StreamLogs>(_list133.size);
          StreamLogs _elem134;
          for (int _i135 = 0; _i135 < _list133.size; ++_i135)
          {
            _elem134 = new StreamLogs();
            _elem134.read(iprot);
            struct.multiStreamLogs.add(_elem134);
          }
        }
        struct.setMultiStreamLogsIsSet(true);
      }
    }
  }

  private static <S extends org.apache.thrift.scheme.IScheme> S scheme(org.apache.thrift.protocol.TProtocol proto) {
    return (org.apache.thrift.scheme.StandardScheme.class.equals(proto.getScheme()) ? STANDARD_SCHEME_FACTORY : TUPLE_SCHEME_FACTORY).getScheme();
  }
}

