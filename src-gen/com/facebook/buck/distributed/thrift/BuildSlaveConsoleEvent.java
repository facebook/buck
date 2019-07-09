/**
 * Autogenerated by Thrift Compiler (0.12.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package com.facebook.buck.distributed.thrift;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.12.0)")
public class BuildSlaveConsoleEvent implements org.apache.thrift.TBase<BuildSlaveConsoleEvent, BuildSlaveConsoleEvent._Fields>, java.io.Serializable, Cloneable, Comparable<BuildSlaveConsoleEvent> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("BuildSlaveConsoleEvent");

  private static final org.apache.thrift.protocol.TField MESSAGE_FIELD_DESC = new org.apache.thrift.protocol.TField("message", org.apache.thrift.protocol.TType.STRING, (short)1);
  private static final org.apache.thrift.protocol.TField SEVERITY_FIELD_DESC = new org.apache.thrift.protocol.TField("severity", org.apache.thrift.protocol.TType.I32, (short)2);

  private static final org.apache.thrift.scheme.SchemeFactory STANDARD_SCHEME_FACTORY = new BuildSlaveConsoleEventStandardSchemeFactory();
  private static final org.apache.thrift.scheme.SchemeFactory TUPLE_SCHEME_FACTORY = new BuildSlaveConsoleEventTupleSchemeFactory();

  public @org.apache.thrift.annotation.Nullable java.lang.String message; // optional
  /**
   * 
   * @see ConsoleEventSeverity
   */
  public @org.apache.thrift.annotation.Nullable ConsoleEventSeverity severity; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    MESSAGE((short)1, "message"),
    /**
     * 
     * @see ConsoleEventSeverity
     */
    SEVERITY((short)2, "severity");

    private static final java.util.Map<java.lang.String, _Fields> byName = new java.util.HashMap<java.lang.String, _Fields>();

    static {
      for (_Fields field : java.util.EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    @org.apache.thrift.annotation.Nullable
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // MESSAGE
          return MESSAGE;
        case 2: // SEVERITY
          return SEVERITY;
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
    @org.apache.thrift.annotation.Nullable
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
  private static final _Fields optionals[] = {_Fields.MESSAGE,_Fields.SEVERITY};
  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.MESSAGE, new org.apache.thrift.meta_data.FieldMetaData("message", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.SEVERITY, new org.apache.thrift.meta_data.FieldMetaData("severity", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.EnumMetaData(org.apache.thrift.protocol.TType.ENUM, ConsoleEventSeverity.class)));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(BuildSlaveConsoleEvent.class, metaDataMap);
  }

  public BuildSlaveConsoleEvent() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public BuildSlaveConsoleEvent(BuildSlaveConsoleEvent other) {
    if (other.isSetMessage()) {
      this.message = other.message;
    }
    if (other.isSetSeverity()) {
      this.severity = other.severity;
    }
  }

  public BuildSlaveConsoleEvent deepCopy() {
    return new BuildSlaveConsoleEvent(this);
  }

  @Override
  public void clear() {
    this.message = null;
    this.severity = null;
  }

  @org.apache.thrift.annotation.Nullable
  public java.lang.String getMessage() {
    return this.message;
  }

  public BuildSlaveConsoleEvent setMessage(@org.apache.thrift.annotation.Nullable java.lang.String message) {
    this.message = message;
    return this;
  }

  public void unsetMessage() {
    this.message = null;
  }

  /** Returns true if field message is set (has been assigned a value) and false otherwise */
  public boolean isSetMessage() {
    return this.message != null;
  }

  public void setMessageIsSet(boolean value) {
    if (!value) {
      this.message = null;
    }
  }

  /**
   * 
   * @see ConsoleEventSeverity
   */
  @org.apache.thrift.annotation.Nullable
  public ConsoleEventSeverity getSeverity() {
    return this.severity;
  }

  /**
   * 
   * @see ConsoleEventSeverity
   */
  public BuildSlaveConsoleEvent setSeverity(@org.apache.thrift.annotation.Nullable ConsoleEventSeverity severity) {
    this.severity = severity;
    return this;
  }

  public void unsetSeverity() {
    this.severity = null;
  }

  /** Returns true if field severity is set (has been assigned a value) and false otherwise */
  public boolean isSetSeverity() {
    return this.severity != null;
  }

  public void setSeverityIsSet(boolean value) {
    if (!value) {
      this.severity = null;
    }
  }

  public void setFieldValue(_Fields field, @org.apache.thrift.annotation.Nullable java.lang.Object value) {
    switch (field) {
    case MESSAGE:
      if (value == null) {
        unsetMessage();
      } else {
        setMessage((java.lang.String)value);
      }
      break;

    case SEVERITY:
      if (value == null) {
        unsetSeverity();
      } else {
        setSeverity((ConsoleEventSeverity)value);
      }
      break;

    }
  }

  @org.apache.thrift.annotation.Nullable
  public java.lang.Object getFieldValue(_Fields field) {
    switch (field) {
    case MESSAGE:
      return getMessage();

    case SEVERITY:
      return getSeverity();

    }
    throw new java.lang.IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new java.lang.IllegalArgumentException();
    }

    switch (field) {
    case MESSAGE:
      return isSetMessage();
    case SEVERITY:
      return isSetSeverity();
    }
    throw new java.lang.IllegalStateException();
  }

  @Override
  public boolean equals(java.lang.Object that) {
    if (that == null)
      return false;
    if (that instanceof BuildSlaveConsoleEvent)
      return this.equals((BuildSlaveConsoleEvent)that);
    return false;
  }

  public boolean equals(BuildSlaveConsoleEvent that) {
    if (that == null)
      return false;
    if (this == that)
      return true;

    boolean this_present_message = true && this.isSetMessage();
    boolean that_present_message = true && that.isSetMessage();
    if (this_present_message || that_present_message) {
      if (!(this_present_message && that_present_message))
        return false;
      if (!this.message.equals(that.message))
        return false;
    }

    boolean this_present_severity = true && this.isSetSeverity();
    boolean that_present_severity = true && that.isSetSeverity();
    if (this_present_severity || that_present_severity) {
      if (!(this_present_severity && that_present_severity))
        return false;
      if (!this.severity.equals(that.severity))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int hashCode = 1;

    hashCode = hashCode * 8191 + ((isSetMessage()) ? 131071 : 524287);
    if (isSetMessage())
      hashCode = hashCode * 8191 + message.hashCode();

    hashCode = hashCode * 8191 + ((isSetSeverity()) ? 131071 : 524287);
    if (isSetSeverity())
      hashCode = hashCode * 8191 + severity.getValue();

    return hashCode;
  }

  @Override
  public int compareTo(BuildSlaveConsoleEvent other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = java.lang.Boolean.valueOf(isSetMessage()).compareTo(other.isSetMessage());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetMessage()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.message, other.message);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = java.lang.Boolean.valueOf(isSetSeverity()).compareTo(other.isSetSeverity());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetSeverity()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.severity, other.severity);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  @org.apache.thrift.annotation.Nullable
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
    java.lang.StringBuilder sb = new java.lang.StringBuilder("BuildSlaveConsoleEvent(");
    boolean first = true;

    if (isSetMessage()) {
      sb.append("message:");
      if (this.message == null) {
        sb.append("null");
      } else {
        sb.append(this.message);
      }
      first = false;
    }
    if (isSetSeverity()) {
      if (!first) sb.append(", ");
      sb.append("severity:");
      if (this.severity == null) {
        sb.append("null");
      } else {
        sb.append(this.severity);
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

  private static class BuildSlaveConsoleEventStandardSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public BuildSlaveConsoleEventStandardScheme getScheme() {
      return new BuildSlaveConsoleEventStandardScheme();
    }
  }

  private static class BuildSlaveConsoleEventStandardScheme extends org.apache.thrift.scheme.StandardScheme<BuildSlaveConsoleEvent> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, BuildSlaveConsoleEvent struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // MESSAGE
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.message = iprot.readString();
              struct.setMessageIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // SEVERITY
            if (schemeField.type == org.apache.thrift.protocol.TType.I32) {
              struct.severity = com.facebook.buck.distributed.thrift.ConsoleEventSeverity.findByValue(iprot.readI32());
              struct.setSeverityIsSet(true);
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

    public void write(org.apache.thrift.protocol.TProtocol oprot, BuildSlaveConsoleEvent struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.message != null) {
        if (struct.isSetMessage()) {
          oprot.writeFieldBegin(MESSAGE_FIELD_DESC);
          oprot.writeString(struct.message);
          oprot.writeFieldEnd();
        }
      }
      if (struct.severity != null) {
        if (struct.isSetSeverity()) {
          oprot.writeFieldBegin(SEVERITY_FIELD_DESC);
          oprot.writeI32(struct.severity.getValue());
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class BuildSlaveConsoleEventTupleSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public BuildSlaveConsoleEventTupleScheme getScheme() {
      return new BuildSlaveConsoleEventTupleScheme();
    }
  }

  private static class BuildSlaveConsoleEventTupleScheme extends org.apache.thrift.scheme.TupleScheme<BuildSlaveConsoleEvent> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, BuildSlaveConsoleEvent struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol oprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet optionals = new java.util.BitSet();
      if (struct.isSetMessage()) {
        optionals.set(0);
      }
      if (struct.isSetSeverity()) {
        optionals.set(1);
      }
      oprot.writeBitSet(optionals, 2);
      if (struct.isSetMessage()) {
        oprot.writeString(struct.message);
      }
      if (struct.isSetSeverity()) {
        oprot.writeI32(struct.severity.getValue());
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, BuildSlaveConsoleEvent struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol iprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet incoming = iprot.readBitSet(2);
      if (incoming.get(0)) {
        struct.message = iprot.readString();
        struct.setMessageIsSet(true);
      }
      if (incoming.get(1)) {
        struct.severity = com.facebook.buck.distributed.thrift.ConsoleEventSeverity.findByValue(iprot.readI32());
        struct.setSeverityIsSet(true);
      }
    }
  }

  private static <S extends org.apache.thrift.scheme.IScheme> S scheme(org.apache.thrift.protocol.TProtocol proto) {
    return (org.apache.thrift.scheme.StandardScheme.class.equals(proto.getScheme()) ? STANDARD_SCHEME_FACTORY : TUPLE_SCHEME_FACTORY).getScheme();
  }
}

