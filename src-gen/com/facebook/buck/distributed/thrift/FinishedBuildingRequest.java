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
@Generated(value = "Autogenerated by Thrift Compiler (0.9.3)", date = "2017-02-19")
public class FinishedBuildingRequest implements org.apache.thrift.TBase<FinishedBuildingRequest, FinishedBuildingRequest._Fields>, java.io.Serializable, Cloneable, Comparable<FinishedBuildingRequest> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("FinishedBuildingRequest");

  private static final org.apache.thrift.protocol.TField MINION_ID_FIELD_DESC = new org.apache.thrift.protocol.TField("minionId", org.apache.thrift.protocol.TType.STRING, (short)1);
  private static final org.apache.thrift.protocol.TField BUILD_EXIT_CODE_FIELD_DESC = new org.apache.thrift.protocol.TField("buildExitCode", org.apache.thrift.protocol.TType.I32, (short)2);
  private static final org.apache.thrift.protocol.TField STAMPEDE_ID_FIELD_DESC = new org.apache.thrift.protocol.TField("stampedeId", org.apache.thrift.protocol.TType.STRUCT, (short)3);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new FinishedBuildingRequestStandardSchemeFactory());
    schemes.put(TupleScheme.class, new FinishedBuildingRequestTupleSchemeFactory());
  }

  public String minionId; // optional
  public int buildExitCode; // optional
  public com.facebook.buck.distributed.thrift.StampedeId stampedeId; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    MINION_ID((short)1, "minionId"),
    BUILD_EXIT_CODE((short)2, "buildExitCode"),
    STAMPEDE_ID((short)3, "stampedeId");

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
        case 1: // MINION_ID
          return MINION_ID;
        case 2: // BUILD_EXIT_CODE
          return BUILD_EXIT_CODE;
        case 3: // STAMPEDE_ID
          return STAMPEDE_ID;
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
  private static final int __BUILDEXITCODE_ISSET_ID = 0;
  private byte __isset_bitfield = 0;
  private static final _Fields optionals[] = {_Fields.MINION_ID,_Fields.BUILD_EXIT_CODE,_Fields.STAMPEDE_ID};
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.MINION_ID, new org.apache.thrift.meta_data.FieldMetaData("minionId", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.BUILD_EXIT_CODE, new org.apache.thrift.meta_data.FieldMetaData("buildExitCode", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I32)));
    tmpMap.put(_Fields.STAMPEDE_ID, new org.apache.thrift.meta_data.FieldMetaData("stampedeId", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, com.facebook.buck.distributed.thrift.StampedeId.class)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(FinishedBuildingRequest.class, metaDataMap);
  }

  public FinishedBuildingRequest() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public FinishedBuildingRequest(FinishedBuildingRequest other) {
    __isset_bitfield = other.__isset_bitfield;
    if (other.isSetMinionId()) {
      this.minionId = other.minionId;
    }
    this.buildExitCode = other.buildExitCode;
    if (other.isSetStampedeId()) {
      this.stampedeId = new com.facebook.buck.distributed.thrift.StampedeId(other.stampedeId);
    }
  }

  public FinishedBuildingRequest deepCopy() {
    return new FinishedBuildingRequest(this);
  }

  @Override
  public void clear() {
    this.minionId = null;
    setBuildExitCodeIsSet(false);
    this.buildExitCode = 0;
    this.stampedeId = null;
  }

  public String getMinionId() {
    return this.minionId;
  }

  public FinishedBuildingRequest setMinionId(String minionId) {
    this.minionId = minionId;
    return this;
  }

  public void unsetMinionId() {
    this.minionId = null;
  }

  /** Returns true if field minionId is set (has been assigned a value) and false otherwise */
  public boolean isSetMinionId() {
    return this.minionId != null;
  }

  public void setMinionIdIsSet(boolean value) {
    if (!value) {
      this.minionId = null;
    }
  }

  public int getBuildExitCode() {
    return this.buildExitCode;
  }

  public FinishedBuildingRequest setBuildExitCode(int buildExitCode) {
    this.buildExitCode = buildExitCode;
    setBuildExitCodeIsSet(true);
    return this;
  }

  public void unsetBuildExitCode() {
    __isset_bitfield = EncodingUtils.clearBit(__isset_bitfield, __BUILDEXITCODE_ISSET_ID);
  }

  /** Returns true if field buildExitCode is set (has been assigned a value) and false otherwise */
  public boolean isSetBuildExitCode() {
    return EncodingUtils.testBit(__isset_bitfield, __BUILDEXITCODE_ISSET_ID);
  }

  public void setBuildExitCodeIsSet(boolean value) {
    __isset_bitfield = EncodingUtils.setBit(__isset_bitfield, __BUILDEXITCODE_ISSET_ID, value);
  }

  public com.facebook.buck.distributed.thrift.StampedeId getStampedeId() {
    return this.stampedeId;
  }

  public FinishedBuildingRequest setStampedeId(com.facebook.buck.distributed.thrift.StampedeId stampedeId) {
    this.stampedeId = stampedeId;
    return this;
  }

  public void unsetStampedeId() {
    this.stampedeId = null;
  }

  /** Returns true if field stampedeId is set (has been assigned a value) and false otherwise */
  public boolean isSetStampedeId() {
    return this.stampedeId != null;
  }

  public void setStampedeIdIsSet(boolean value) {
    if (!value) {
      this.stampedeId = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case MINION_ID:
      if (value == null) {
        unsetMinionId();
      } else {
        setMinionId((String)value);
      }
      break;

    case BUILD_EXIT_CODE:
      if (value == null) {
        unsetBuildExitCode();
      } else {
        setBuildExitCode((Integer)value);
      }
      break;

    case STAMPEDE_ID:
      if (value == null) {
        unsetStampedeId();
      } else {
        setStampedeId((com.facebook.buck.distributed.thrift.StampedeId)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case MINION_ID:
      return getMinionId();

    case BUILD_EXIT_CODE:
      return getBuildExitCode();

    case STAMPEDE_ID:
      return getStampedeId();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case MINION_ID:
      return isSetMinionId();
    case BUILD_EXIT_CODE:
      return isSetBuildExitCode();
    case STAMPEDE_ID:
      return isSetStampedeId();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof FinishedBuildingRequest)
      return this.equals((FinishedBuildingRequest)that);
    return false;
  }

  public boolean equals(FinishedBuildingRequest that) {
    if (that == null)
      return false;

    boolean this_present_minionId = true && this.isSetMinionId();
    boolean that_present_minionId = true && that.isSetMinionId();
    if (this_present_minionId || that_present_minionId) {
      if (!(this_present_minionId && that_present_minionId))
        return false;
      if (!this.minionId.equals(that.minionId))
        return false;
    }

    boolean this_present_buildExitCode = true && this.isSetBuildExitCode();
    boolean that_present_buildExitCode = true && that.isSetBuildExitCode();
    if (this_present_buildExitCode || that_present_buildExitCode) {
      if (!(this_present_buildExitCode && that_present_buildExitCode))
        return false;
      if (this.buildExitCode != that.buildExitCode)
        return false;
    }

    boolean this_present_stampedeId = true && this.isSetStampedeId();
    boolean that_present_stampedeId = true && that.isSetStampedeId();
    if (this_present_stampedeId || that_present_stampedeId) {
      if (!(this_present_stampedeId && that_present_stampedeId))
        return false;
      if (!this.stampedeId.equals(that.stampedeId))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_minionId = true && (isSetMinionId());
    list.add(present_minionId);
    if (present_minionId)
      list.add(minionId);

    boolean present_buildExitCode = true && (isSetBuildExitCode());
    list.add(present_buildExitCode);
    if (present_buildExitCode)
      list.add(buildExitCode);

    boolean present_stampedeId = true && (isSetStampedeId());
    list.add(present_stampedeId);
    if (present_stampedeId)
      list.add(stampedeId);

    return list.hashCode();
  }

  @Override
  public int compareTo(FinishedBuildingRequest other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(isSetMinionId()).compareTo(other.isSetMinionId());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetMinionId()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.minionId, other.minionId);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetBuildExitCode()).compareTo(other.isSetBuildExitCode());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetBuildExitCode()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.buildExitCode, other.buildExitCode);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetStampedeId()).compareTo(other.isSetStampedeId());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetStampedeId()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.stampedeId, other.stampedeId);
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
    StringBuilder sb = new StringBuilder("FinishedBuildingRequest(");
    boolean first = true;

    if (isSetMinionId()) {
      sb.append("minionId:");
      if (this.minionId == null) {
        sb.append("null");
      } else {
        sb.append(this.minionId);
      }
      first = false;
    }
    if (isSetBuildExitCode()) {
      if (!first) sb.append(", ");
      sb.append("buildExitCode:");
      sb.append(this.buildExitCode);
      first = false;
    }
    if (isSetStampedeId()) {
      if (!first) sb.append(", ");
      sb.append("stampedeId:");
      if (this.stampedeId == null) {
        sb.append("null");
      } else {
        sb.append(this.stampedeId);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
    if (stampedeId != null) {
      stampedeId.validate();
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

  private static class FinishedBuildingRequestStandardSchemeFactory implements SchemeFactory {
    public FinishedBuildingRequestStandardScheme getScheme() {
      return new FinishedBuildingRequestStandardScheme();
    }
  }

  private static class FinishedBuildingRequestStandardScheme extends StandardScheme<FinishedBuildingRequest> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, FinishedBuildingRequest struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // MINION_ID
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.minionId = iprot.readString();
              struct.setMinionIdIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // BUILD_EXIT_CODE
            if (schemeField.type == org.apache.thrift.protocol.TType.I32) {
              struct.buildExitCode = iprot.readI32();
              struct.setBuildExitCodeIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // STAMPEDE_ID
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.stampedeId = new com.facebook.buck.distributed.thrift.StampedeId();
              struct.stampedeId.read(iprot);
              struct.setStampedeIdIsSet(true);
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

    public void write(org.apache.thrift.protocol.TProtocol oprot, FinishedBuildingRequest struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.minionId != null) {
        if (struct.isSetMinionId()) {
          oprot.writeFieldBegin(MINION_ID_FIELD_DESC);
          oprot.writeString(struct.minionId);
          oprot.writeFieldEnd();
        }
      }
      if (struct.isSetBuildExitCode()) {
        oprot.writeFieldBegin(BUILD_EXIT_CODE_FIELD_DESC);
        oprot.writeI32(struct.buildExitCode);
        oprot.writeFieldEnd();
      }
      if (struct.stampedeId != null) {
        if (struct.isSetStampedeId()) {
          oprot.writeFieldBegin(STAMPEDE_ID_FIELD_DESC);
          struct.stampedeId.write(oprot);
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class FinishedBuildingRequestTupleSchemeFactory implements SchemeFactory {
    public FinishedBuildingRequestTupleScheme getScheme() {
      return new FinishedBuildingRequestTupleScheme();
    }
  }

  private static class FinishedBuildingRequestTupleScheme extends TupleScheme<FinishedBuildingRequest> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, FinishedBuildingRequest struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      BitSet optionals = new BitSet();
      if (struct.isSetMinionId()) {
        optionals.set(0);
      }
      if (struct.isSetBuildExitCode()) {
        optionals.set(1);
      }
      if (struct.isSetStampedeId()) {
        optionals.set(2);
      }
      oprot.writeBitSet(optionals, 3);
      if (struct.isSetMinionId()) {
        oprot.writeString(struct.minionId);
      }
      if (struct.isSetBuildExitCode()) {
        oprot.writeI32(struct.buildExitCode);
      }
      if (struct.isSetStampedeId()) {
        struct.stampedeId.write(oprot);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, FinishedBuildingRequest struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      BitSet incoming = iprot.readBitSet(3);
      if (incoming.get(0)) {
        struct.minionId = iprot.readString();
        struct.setMinionIdIsSet(true);
      }
      if (incoming.get(1)) {
        struct.buildExitCode = iprot.readI32();
        struct.setBuildExitCodeIsSet(true);
      }
      if (incoming.get(2)) {
        struct.stampedeId = new com.facebook.buck.distributed.thrift.StampedeId();
        struct.stampedeId.read(iprot);
        struct.setStampedeIdIsSet(true);
      }
    }
  }

}

