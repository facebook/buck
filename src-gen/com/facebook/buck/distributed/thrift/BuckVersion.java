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
@Generated(value = "Autogenerated by Thrift Compiler (0.9.3)", date = "2017-06-06")
public class BuckVersion implements org.apache.thrift.TBase<BuckVersion, BuckVersion._Fields>, java.io.Serializable, Cloneable, Comparable<BuckVersion> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("BuckVersion");

  private static final org.apache.thrift.protocol.TField TYPE_FIELD_DESC = new org.apache.thrift.protocol.TField("type", org.apache.thrift.protocol.TType.I32, (short)1);
  private static final org.apache.thrift.protocol.TField GIT_HASH_FIELD_DESC = new org.apache.thrift.protocol.TField("gitHash", org.apache.thrift.protocol.TType.STRING, (short)2);
  private static final org.apache.thrift.protocol.TField DEVELOPMENT_VERSION_FIELD_DESC = new org.apache.thrift.protocol.TField("developmentVersion", org.apache.thrift.protocol.TType.STRUCT, (short)3);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new BuckVersionStandardSchemeFactory());
    schemes.put(TupleScheme.class, new BuckVersionTupleSchemeFactory());
  }

  /**
   * 
   * @see BuckVersionType
   */
  public BuckVersionType type; // optional
  public String gitHash; // optional
  public FileInfo developmentVersion; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    /**
     * 
     * @see BuckVersionType
     */
    TYPE((short)1, "type"),
    GIT_HASH((short)2, "gitHash"),
    DEVELOPMENT_VERSION((short)3, "developmentVersion");

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
        case 1: // TYPE
          return TYPE;
        case 2: // GIT_HASH
          return GIT_HASH;
        case 3: // DEVELOPMENT_VERSION
          return DEVELOPMENT_VERSION;
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
  private static final _Fields optionals[] = {_Fields.TYPE,_Fields.GIT_HASH,_Fields.DEVELOPMENT_VERSION};
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.TYPE, new org.apache.thrift.meta_data.FieldMetaData("type", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.EnumMetaData(org.apache.thrift.protocol.TType.ENUM, BuckVersionType.class)));
    tmpMap.put(_Fields.GIT_HASH, new org.apache.thrift.meta_data.FieldMetaData("gitHash", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.DEVELOPMENT_VERSION, new org.apache.thrift.meta_data.FieldMetaData("developmentVersion", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, FileInfo.class)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(BuckVersion.class, metaDataMap);
  }

  public BuckVersion() {
    this.type = com.facebook.buck.distributed.thrift.BuckVersionType.UNKNOWN;

  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public BuckVersion(BuckVersion other) {
    if (other.isSetType()) {
      this.type = other.type;
    }
    if (other.isSetGitHash()) {
      this.gitHash = other.gitHash;
    }
    if (other.isSetDevelopmentVersion()) {
      this.developmentVersion = new FileInfo(other.developmentVersion);
    }
  }

  public BuckVersion deepCopy() {
    return new BuckVersion(this);
  }

  @Override
  public void clear() {
    this.type = com.facebook.buck.distributed.thrift.BuckVersionType.UNKNOWN;

    this.gitHash = null;
    this.developmentVersion = null;
  }

  /**
   * 
   * @see BuckVersionType
   */
  public BuckVersionType getType() {
    return this.type;
  }

  /**
   * 
   * @see BuckVersionType
   */
  public BuckVersion setType(BuckVersionType type) {
    this.type = type;
    return this;
  }

  public void unsetType() {
    this.type = null;
  }

  /** Returns true if field type is set (has been assigned a value) and false otherwise */
  public boolean isSetType() {
    return this.type != null;
  }

  public void setTypeIsSet(boolean value) {
    if (!value) {
      this.type = null;
    }
  }

  public String getGitHash() {
    return this.gitHash;
  }

  public BuckVersion setGitHash(String gitHash) {
    this.gitHash = gitHash;
    return this;
  }

  public void unsetGitHash() {
    this.gitHash = null;
  }

  /** Returns true if field gitHash is set (has been assigned a value) and false otherwise */
  public boolean isSetGitHash() {
    return this.gitHash != null;
  }

  public void setGitHashIsSet(boolean value) {
    if (!value) {
      this.gitHash = null;
    }
  }

  public FileInfo getDevelopmentVersion() {
    return this.developmentVersion;
  }

  public BuckVersion setDevelopmentVersion(FileInfo developmentVersion) {
    this.developmentVersion = developmentVersion;
    return this;
  }

  public void unsetDevelopmentVersion() {
    this.developmentVersion = null;
  }

  /** Returns true if field developmentVersion is set (has been assigned a value) and false otherwise */
  public boolean isSetDevelopmentVersion() {
    return this.developmentVersion != null;
  }

  public void setDevelopmentVersionIsSet(boolean value) {
    if (!value) {
      this.developmentVersion = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case TYPE:
      if (value == null) {
        unsetType();
      } else {
        setType((BuckVersionType)value);
      }
      break;

    case GIT_HASH:
      if (value == null) {
        unsetGitHash();
      } else {
        setGitHash((String)value);
      }
      break;

    case DEVELOPMENT_VERSION:
      if (value == null) {
        unsetDevelopmentVersion();
      } else {
        setDevelopmentVersion((FileInfo)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case TYPE:
      return getType();

    case GIT_HASH:
      return getGitHash();

    case DEVELOPMENT_VERSION:
      return getDevelopmentVersion();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case TYPE:
      return isSetType();
    case GIT_HASH:
      return isSetGitHash();
    case DEVELOPMENT_VERSION:
      return isSetDevelopmentVersion();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof BuckVersion)
      return this.equals((BuckVersion)that);
    return false;
  }

  public boolean equals(BuckVersion that) {
    if (that == null)
      return false;

    boolean this_present_type = true && this.isSetType();
    boolean that_present_type = true && that.isSetType();
    if (this_present_type || that_present_type) {
      if (!(this_present_type && that_present_type))
        return false;
      if (!this.type.equals(that.type))
        return false;
    }

    boolean this_present_gitHash = true && this.isSetGitHash();
    boolean that_present_gitHash = true && that.isSetGitHash();
    if (this_present_gitHash || that_present_gitHash) {
      if (!(this_present_gitHash && that_present_gitHash))
        return false;
      if (!this.gitHash.equals(that.gitHash))
        return false;
    }

    boolean this_present_developmentVersion = true && this.isSetDevelopmentVersion();
    boolean that_present_developmentVersion = true && that.isSetDevelopmentVersion();
    if (this_present_developmentVersion || that_present_developmentVersion) {
      if (!(this_present_developmentVersion && that_present_developmentVersion))
        return false;
      if (!this.developmentVersion.equals(that.developmentVersion))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_type = true && (isSetType());
    list.add(present_type);
    if (present_type)
      list.add(type.getValue());

    boolean present_gitHash = true && (isSetGitHash());
    list.add(present_gitHash);
    if (present_gitHash)
      list.add(gitHash);

    boolean present_developmentVersion = true && (isSetDevelopmentVersion());
    list.add(present_developmentVersion);
    if (present_developmentVersion)
      list.add(developmentVersion);

    return list.hashCode();
  }

  @Override
  public int compareTo(BuckVersion other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(isSetType()).compareTo(other.isSetType());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetType()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.type, other.type);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetGitHash()).compareTo(other.isSetGitHash());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetGitHash()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.gitHash, other.gitHash);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetDevelopmentVersion()).compareTo(other.isSetDevelopmentVersion());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetDevelopmentVersion()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.developmentVersion, other.developmentVersion);
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
    StringBuilder sb = new StringBuilder("BuckVersion(");
    boolean first = true;

    if (isSetType()) {
      sb.append("type:");
      if (this.type == null) {
        sb.append("null");
      } else {
        sb.append(this.type);
      }
      first = false;
    }
    if (isSetGitHash()) {
      if (!first) sb.append(", ");
      sb.append("gitHash:");
      if (this.gitHash == null) {
        sb.append("null");
      } else {
        sb.append(this.gitHash);
      }
      first = false;
    }
    if (isSetDevelopmentVersion()) {
      if (!first) sb.append(", ");
      sb.append("developmentVersion:");
      if (this.developmentVersion == null) {
        sb.append("null");
      } else {
        sb.append(this.developmentVersion);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
    if (developmentVersion != null) {
      developmentVersion.validate();
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
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class BuckVersionStandardSchemeFactory implements SchemeFactory {
    public BuckVersionStandardScheme getScheme() {
      return new BuckVersionStandardScheme();
    }
  }

  private static class BuckVersionStandardScheme extends StandardScheme<BuckVersion> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, BuckVersion struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // TYPE
            if (schemeField.type == org.apache.thrift.protocol.TType.I32) {
              struct.type = com.facebook.buck.distributed.thrift.BuckVersionType.findByValue(iprot.readI32());
              struct.setTypeIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // GIT_HASH
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.gitHash = iprot.readString();
              struct.setGitHashIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // DEVELOPMENT_VERSION
            if (schemeField.type == org.apache.thrift.protocol.TType.STRUCT) {
              struct.developmentVersion = new FileInfo();
              struct.developmentVersion.read(iprot);
              struct.setDevelopmentVersionIsSet(true);
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

    public void write(org.apache.thrift.protocol.TProtocol oprot, BuckVersion struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.type != null) {
        if (struct.isSetType()) {
          oprot.writeFieldBegin(TYPE_FIELD_DESC);
          oprot.writeI32(struct.type.getValue());
          oprot.writeFieldEnd();
        }
      }
      if (struct.gitHash != null) {
        if (struct.isSetGitHash()) {
          oprot.writeFieldBegin(GIT_HASH_FIELD_DESC);
          oprot.writeString(struct.gitHash);
          oprot.writeFieldEnd();
        }
      }
      if (struct.developmentVersion != null) {
        if (struct.isSetDevelopmentVersion()) {
          oprot.writeFieldBegin(DEVELOPMENT_VERSION_FIELD_DESC);
          struct.developmentVersion.write(oprot);
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class BuckVersionTupleSchemeFactory implements SchemeFactory {
    public BuckVersionTupleScheme getScheme() {
      return new BuckVersionTupleScheme();
    }
  }

  private static class BuckVersionTupleScheme extends TupleScheme<BuckVersion> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, BuckVersion struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      BitSet optionals = new BitSet();
      if (struct.isSetType()) {
        optionals.set(0);
      }
      if (struct.isSetGitHash()) {
        optionals.set(1);
      }
      if (struct.isSetDevelopmentVersion()) {
        optionals.set(2);
      }
      oprot.writeBitSet(optionals, 3);
      if (struct.isSetType()) {
        oprot.writeI32(struct.type.getValue());
      }
      if (struct.isSetGitHash()) {
        oprot.writeString(struct.gitHash);
      }
      if (struct.isSetDevelopmentVersion()) {
        struct.developmentVersion.write(oprot);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, BuckVersion struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      BitSet incoming = iprot.readBitSet(3);
      if (incoming.get(0)) {
        struct.type = com.facebook.buck.distributed.thrift.BuckVersionType.findByValue(iprot.readI32());
        struct.setTypeIsSet(true);
      }
      if (incoming.get(1)) {
        struct.gitHash = iprot.readString();
        struct.setGitHashIsSet(true);
      }
      if (incoming.get(2)) {
        struct.developmentVersion = new FileInfo();
        struct.developmentVersion.read(iprot);
        struct.setDevelopmentVersionIsSet(true);
      }
    }
  }

}

