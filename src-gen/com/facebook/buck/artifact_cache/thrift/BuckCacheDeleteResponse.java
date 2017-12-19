/**
 * Autogenerated by Thrift Compiler (0.10.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package com.facebook.buck.artifact_cache.thrift;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.10.0)", date = "2017-12-07")
public class BuckCacheDeleteResponse implements org.apache.thrift.TBase<BuckCacheDeleteResponse, BuckCacheDeleteResponse._Fields>, java.io.Serializable, Cloneable, Comparable<BuckCacheDeleteResponse> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("BuckCacheDeleteResponse");

  private static final org.apache.thrift.protocol.TField DEBUG_INFO_FIELD_DESC = new org.apache.thrift.protocol.TField("debugInfo", org.apache.thrift.protocol.TType.STRUCT, (short)1);

  private static final org.apache.thrift.scheme.SchemeFactory STANDARD_SCHEME_FACTORY = new BuckCacheDeleteResponseStandardSchemeFactory();
  private static final org.apache.thrift.scheme.SchemeFactory TUPLE_SCHEME_FACTORY = new BuckCacheDeleteResponseTupleSchemeFactory();

  public DeleteDebugInfo debugInfo; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    DEBUG_INFO((short)1, "debugInfo");

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
        case 1: // DEBUG_INFO
          return DEBUG_INFO;
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
  private static final _Fields optionals[] = {_Fields.DEBUG_INFO};
  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.DEBUG_INFO, new org.apache.thrift.meta_data.FieldMetaData("debugInfo", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, DeleteDebugInfo.class)));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(BuckCacheDeleteResponse.class, metaDataMap);
  }

  public BuckCacheDeleteResponse() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public BuckCacheDeleteResponse(BuckCacheDeleteResponse other) {
    if (other.isSetDebugInfo()) {
      this.debugInfo = new DeleteDebugInfo(other.debugInfo);
    }
  }

  public BuckCacheDeleteResponse deepCopy() {
    return new BuckCacheDeleteResponse(this);
  }

  @Override
  public void clear() {
    this.debugInfo = null;
  }

  public DeleteDebugInfo getDebugInfo() {
    return this.debugInfo;
  }

  public BuckCacheDeleteResponse setDebugInfo(DeleteDebugInfo debugInfo) {
    this.debugInfo = debugInfo;
    return this;
  }

  public void unsetDebugInfo() {
    this.debugInfo = null;
  }

  /** Returns true if field debugInfo is set (has been assigned a value) and false otherwise */
  public boolean isSetDebugInfo() {
    return this.debugInfo != null;
  }

  public void setDebugInfoIsSet(boolean value) {
    if (!value) {
      this.debugInfo = null;
    }
  }

  public void setFieldValue(_Fields field, java.lang.Object value) {
    switch (field) {
    case DEBUG_INFO:
      if (value == null) {
        unsetDebugInfo();
      } else {
        setDebugInfo((DeleteDebugInfo)value);
      }
      break;

    }
  }

  public java.lang.Object getFieldValue(_Fields field) {
    switch (field) {
    case DEBUG_INFO:
      return getDebugInfo();

    }
    throw new java.lang.IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new java.lang.IllegalArgumentException();
    }

    switch (field) {
    case DEBUG_INFO:
      return isSetDebugInfo();
    }
    throw new java.lang.IllegalStateException();
  }

  @Override
  public boolean equals(java.lang.Object that) {
    if (that == null)
      return false;
    if (that instanceof BuckCacheDeleteResponse)
      return this.equals((BuckCacheDeleteResponse)that);
    return false;
  }

  public boolean equals(BuckCacheDeleteResponse that) {
    if (that == null)
      return false;
    if (this == that)
      return true;

    boolean this_present_debugInfo = true && this.isSetDebugInfo();
    boolean that_present_debugInfo = true && that.isSetDebugInfo();
    if (this_present_debugInfo || that_present_debugInfo) {
      if (!(this_present_debugInfo && that_present_debugInfo))
        return false;
      if (!this.debugInfo.equals(that.debugInfo))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int hashCode = 1;

    hashCode = hashCode * 8191 + ((isSetDebugInfo()) ? 131071 : 524287);
    if (isSetDebugInfo())
      hashCode = hashCode * 8191 + debugInfo.hashCode();

    return hashCode;
  }

  @Override
  public int compareTo(BuckCacheDeleteResponse other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = java.lang.Boolean.valueOf(isSetDebugInfo()).compareTo(other.isSetDebugInfo());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetDebugInfo()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.debugInfo, other.debugInfo);
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
    java.lang.StringBuilder sb = new java.lang.StringBuilder("BuckCacheDeleteResponse(");
    boolean first = true;

    if (isSetDebugInfo()) {
      sb.append("debugInfo:");
      if (this.debugInfo == null) {
        sb.append("null");
      } else {
        sb.append(this.debugInfo);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
    if (debugInfo != null) {
      debugInfo.validate();
    }
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

  private static class BuckCacheDeleteResponseStandardSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public BuckCacheDeleteResponseStandardScheme getScheme() {
      return new BuckCacheDeleteResponseStandardScheme();
    }
  }

  private static class BuckCacheDeleteResponseStandardScheme extends org.apache.thrift.scheme.StandardScheme<BuckCacheDeleteResponse> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, BuckCacheDeleteResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // DEBUG_INFO
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.debugInfo = new DeleteDebugInfo();
              struct.debugInfo.read(iprot);
              struct.setDebugInfoIsSet(true);
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

    public void write(org.apache.thrift.protocol.TProtocol oprot, BuckCacheDeleteResponse struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.debugInfo != null) {
        if (struct.isSetDebugInfo()) {
          oprot.writeFieldBegin(DEBUG_INFO_FIELD_DESC);
          struct.debugInfo.write(oprot);
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class BuckCacheDeleteResponseTupleSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public BuckCacheDeleteResponseTupleScheme getScheme() {
      return new BuckCacheDeleteResponseTupleScheme();
    }
  }

  private static class BuckCacheDeleteResponseTupleScheme extends org.apache.thrift.scheme.TupleScheme<BuckCacheDeleteResponse> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, BuckCacheDeleteResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol oprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet optionals = new java.util.BitSet();
      if (struct.isSetDebugInfo()) {
        optionals.set(0);
      }
      oprot.writeBitSet(optionals, 1);
      if (struct.isSetDebugInfo()) {
        struct.debugInfo.write(oprot);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, BuckCacheDeleteResponse struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol iprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet incoming = iprot.readBitSet(1);
      if (incoming.get(0)) {
        struct.debugInfo = new DeleteDebugInfo();
        struct.debugInfo.read(iprot);
        struct.setDebugInfoIsSet(true);
      }
    }
  }

  private static <S extends org.apache.thrift.scheme.IScheme> S scheme(org.apache.thrift.protocol.TProtocol proto) {
    return (org.apache.thrift.scheme.StandardScheme.class.equals(proto.getScheme()) ? STANDARD_SCHEME_FACTORY : TUPLE_SCHEME_FACTORY).getScheme();
  }
}

