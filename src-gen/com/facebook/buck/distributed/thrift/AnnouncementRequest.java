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
public class AnnouncementRequest implements org.apache.thrift.TBase<AnnouncementRequest, AnnouncementRequest._Fields>, java.io.Serializable, Cloneable, Comparable<AnnouncementRequest> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("AnnouncementRequest");

  private static final org.apache.thrift.protocol.TField BUCK_VERSION_FIELD_DESC = new org.apache.thrift.protocol.TField("buckVersion", org.apache.thrift.protocol.TType.STRING, (short)1);
  private static final org.apache.thrift.protocol.TField REPOSITORY_FIELD_DESC = new org.apache.thrift.protocol.TField("repository", org.apache.thrift.protocol.TType.STRING, (short)2);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new AnnouncementRequestStandardSchemeFactory());
    schemes.put(TupleScheme.class, new AnnouncementRequestTupleSchemeFactory());
  }

  public String buckVersion; // optional
  public String repository; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    BUCK_VERSION((short)1, "buckVersion"),
    REPOSITORY((short)2, "repository");

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
        case 1: // BUCK_VERSION
          return BUCK_VERSION;
        case 2: // REPOSITORY
          return REPOSITORY;
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
  private static final _Fields optionals[] = {_Fields.BUCK_VERSION,_Fields.REPOSITORY};
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.BUCK_VERSION, new org.apache.thrift.meta_data.FieldMetaData("buckVersion", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.REPOSITORY, new org.apache.thrift.meta_data.FieldMetaData("repository", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(AnnouncementRequest.class, metaDataMap);
  }

  public AnnouncementRequest() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public AnnouncementRequest(AnnouncementRequest other) {
    if (other.isSetBuckVersion()) {
      this.buckVersion = other.buckVersion;
    }
    if (other.isSetRepository()) {
      this.repository = other.repository;
    }
  }

  public AnnouncementRequest deepCopy() {
    return new AnnouncementRequest(this);
  }

  @Override
  public void clear() {
    this.buckVersion = null;
    this.repository = null;
  }

  public String getBuckVersion() {
    return this.buckVersion;
  }

  public AnnouncementRequest setBuckVersion(String buckVersion) {
    this.buckVersion = buckVersion;
    return this;
  }

  public void unsetBuckVersion() {
    this.buckVersion = null;
  }

  /** Returns true if field buckVersion is set (has been assigned a value) and false otherwise */
  public boolean isSetBuckVersion() {
    return this.buckVersion != null;
  }

  public void setBuckVersionIsSet(boolean value) {
    if (!value) {
      this.buckVersion = null;
    }
  }

  public String getRepository() {
    return this.repository;
  }

  public AnnouncementRequest setRepository(String repository) {
    this.repository = repository;
    return this;
  }

  public void unsetRepository() {
    this.repository = null;
  }

  /** Returns true if field repository is set (has been assigned a value) and false otherwise */
  public boolean isSetRepository() {
    return this.repository != null;
  }

  public void setRepositoryIsSet(boolean value) {
    if (!value) {
      this.repository = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case BUCK_VERSION:
      if (value == null) {
        unsetBuckVersion();
      } else {
        setBuckVersion((String)value);
      }
      break;

    case REPOSITORY:
      if (value == null) {
        unsetRepository();
      } else {
        setRepository((String)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case BUCK_VERSION:
      return getBuckVersion();

    case REPOSITORY:
      return getRepository();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case BUCK_VERSION:
      return isSetBuckVersion();
    case REPOSITORY:
      return isSetRepository();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof AnnouncementRequest)
      return this.equals((AnnouncementRequest)that);
    return false;
  }

  public boolean equals(AnnouncementRequest that) {
    if (that == null)
      return false;

    boolean this_present_buckVersion = true && this.isSetBuckVersion();
    boolean that_present_buckVersion = true && that.isSetBuckVersion();
    if (this_present_buckVersion || that_present_buckVersion) {
      if (!(this_present_buckVersion && that_present_buckVersion))
        return false;
      if (!this.buckVersion.equals(that.buckVersion))
        return false;
    }

    boolean this_present_repository = true && this.isSetRepository();
    boolean that_present_repository = true && that.isSetRepository();
    if (this_present_repository || that_present_repository) {
      if (!(this_present_repository && that_present_repository))
        return false;
      if (!this.repository.equals(that.repository))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_buckVersion = true && (isSetBuckVersion());
    list.add(present_buckVersion);
    if (present_buckVersion)
      list.add(buckVersion);

    boolean present_repository = true && (isSetRepository());
    list.add(present_repository);
    if (present_repository)
      list.add(repository);

    return list.hashCode();
  }

  @Override
  public int compareTo(AnnouncementRequest other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(isSetBuckVersion()).compareTo(other.isSetBuckVersion());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetBuckVersion()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.buckVersion, other.buckVersion);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetRepository()).compareTo(other.isSetRepository());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetRepository()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.repository, other.repository);
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
    StringBuilder sb = new StringBuilder("AnnouncementRequest(");
    boolean first = true;

    if (isSetBuckVersion()) {
      sb.append("buckVersion:");
      if (this.buckVersion == null) {
        sb.append("null");
      } else {
        sb.append(this.buckVersion);
      }
      first = false;
    }
    if (isSetRepository()) {
      if (!first) sb.append(", ");
      sb.append("repository:");
      if (this.repository == null) {
        sb.append("null");
      } else {
        sb.append(this.repository);
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

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
    try {
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class AnnouncementRequestStandardSchemeFactory implements SchemeFactory {
    public AnnouncementRequestStandardScheme getScheme() {
      return new AnnouncementRequestStandardScheme();
    }
  }

  private static class AnnouncementRequestStandardScheme extends StandardScheme<AnnouncementRequest> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, AnnouncementRequest struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // BUCK_VERSION
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.buckVersion = iprot.readString();
              struct.setBuckVersionIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // REPOSITORY
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.repository = iprot.readString();
              struct.setRepositoryIsSet(true);
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

    public void write(org.apache.thrift.protocol.TProtocol oprot, AnnouncementRequest struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.buckVersion != null) {
        if (struct.isSetBuckVersion()) {
          oprot.writeFieldBegin(BUCK_VERSION_FIELD_DESC);
          oprot.writeString(struct.buckVersion);
          oprot.writeFieldEnd();
        }
      }
      if (struct.repository != null) {
        if (struct.isSetRepository()) {
          oprot.writeFieldBegin(REPOSITORY_FIELD_DESC);
          oprot.writeString(struct.repository);
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class AnnouncementRequestTupleSchemeFactory implements SchemeFactory {
    public AnnouncementRequestTupleScheme getScheme() {
      return new AnnouncementRequestTupleScheme();
    }
  }

  private static class AnnouncementRequestTupleScheme extends TupleScheme<AnnouncementRequest> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, AnnouncementRequest struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      BitSet optionals = new BitSet();
      if (struct.isSetBuckVersion()) {
        optionals.set(0);
      }
      if (struct.isSetRepository()) {
        optionals.set(1);
      }
      oprot.writeBitSet(optionals, 2);
      if (struct.isSetBuckVersion()) {
        oprot.writeString(struct.buckVersion);
      }
      if (struct.isSetRepository()) {
        oprot.writeString(struct.repository);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, AnnouncementRequest struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      BitSet incoming = iprot.readBitSet(2);
      if (incoming.get(0)) {
        struct.buckVersion = iprot.readString();
        struct.setBuckVersionIsSet(true);
      }
      if (incoming.get(1)) {
        struct.repository = iprot.readString();
        struct.setRepositoryIsSet(true);
      }
    }
  }

}

