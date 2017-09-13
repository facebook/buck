/**
 * Autogenerated by Thrift Compiler (0.10.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package com.facebook.buck.distributed.thrift;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.10.0)", date = "2017-09-01")
public class RuleKeyStoreLogEntry implements org.apache.thrift.TBase<RuleKeyStoreLogEntry, RuleKeyStoreLogEntry._Fields>, java.io.Serializable, Cloneable, Comparable<RuleKeyStoreLogEntry> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("RuleKeyStoreLogEntry");

  private static final org.apache.thrift.protocol.TField STORE_ID_FIELD_DESC = new org.apache.thrift.protocol.TField("storeId", org.apache.thrift.protocol.TType.STRING, (short)1);
  private static final org.apache.thrift.protocol.TField SLA_SECONDS_FIELD_DESC = new org.apache.thrift.protocol.TField("slaSeconds", org.apache.thrift.protocol.TType.I64, (short)2);
  private static final org.apache.thrift.protocol.TField LAST_STORED_TIMESTAMP_SECONDS_FIELD_DESC = new org.apache.thrift.protocol.TField("lastStoredTimestampSeconds", org.apache.thrift.protocol.TType.I64, (short)3);
  private static final org.apache.thrift.protocol.TField LAST_ATTEMPTED_STORE_TIMETAMP_SECONDS_FIELD_DESC = new org.apache.thrift.protocol.TField("lastAttemptedStoreTimetampSeconds", org.apache.thrift.protocol.TType.I64, (short)4);
  private static final org.apache.thrift.protocol.TField LAST_CACHE_HIT_TIMESTAMP_SECONDS_FIELD_DESC = new org.apache.thrift.protocol.TField("lastCacheHitTimestampSeconds", org.apache.thrift.protocol.TType.I64, (short)5);

  private static final org.apache.thrift.scheme.SchemeFactory STANDARD_SCHEME_FACTORY = new RuleKeyStoreLogEntryStandardSchemeFactory();
  private static final org.apache.thrift.scheme.SchemeFactory TUPLE_SCHEME_FACTORY = new RuleKeyStoreLogEntryTupleSchemeFactory();

  public java.lang.String storeId; // optional
  public long slaSeconds; // optional
  public long lastStoredTimestampSeconds; // optional
  public long lastAttemptedStoreTimetampSeconds; // optional
  public long lastCacheHitTimestampSeconds; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    STORE_ID((short)1, "storeId"),
    SLA_SECONDS((short)2, "slaSeconds"),
    LAST_STORED_TIMESTAMP_SECONDS((short)3, "lastStoredTimestampSeconds"),
    LAST_ATTEMPTED_STORE_TIMETAMP_SECONDS((short)4, "lastAttemptedStoreTimetampSeconds"),
    LAST_CACHE_HIT_TIMESTAMP_SECONDS((short)5, "lastCacheHitTimestampSeconds");

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
        case 1: // STORE_ID
          return STORE_ID;
        case 2: // SLA_SECONDS
          return SLA_SECONDS;
        case 3: // LAST_STORED_TIMESTAMP_SECONDS
          return LAST_STORED_TIMESTAMP_SECONDS;
        case 4: // LAST_ATTEMPTED_STORE_TIMETAMP_SECONDS
          return LAST_ATTEMPTED_STORE_TIMETAMP_SECONDS;
        case 5: // LAST_CACHE_HIT_TIMESTAMP_SECONDS
          return LAST_CACHE_HIT_TIMESTAMP_SECONDS;
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
  private static final int __SLASECONDS_ISSET_ID = 0;
  private static final int __LASTSTOREDTIMESTAMPSECONDS_ISSET_ID = 1;
  private static final int __LASTATTEMPTEDSTORETIMETAMPSECONDS_ISSET_ID = 2;
  private static final int __LASTCACHEHITTIMESTAMPSECONDS_ISSET_ID = 3;
  private byte __isset_bitfield = 0;
  private static final _Fields optionals[] = {_Fields.STORE_ID,_Fields.SLA_SECONDS,_Fields.LAST_STORED_TIMESTAMP_SECONDS,_Fields.LAST_ATTEMPTED_STORE_TIMETAMP_SECONDS,_Fields.LAST_CACHE_HIT_TIMESTAMP_SECONDS};
  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.STORE_ID, new org.apache.thrift.meta_data.FieldMetaData("storeId", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.SLA_SECONDS, new org.apache.thrift.meta_data.FieldMetaData("slaSeconds", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I64)));
    tmpMap.put(_Fields.LAST_STORED_TIMESTAMP_SECONDS, new org.apache.thrift.meta_data.FieldMetaData("lastStoredTimestampSeconds", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I64)));
    tmpMap.put(_Fields.LAST_ATTEMPTED_STORE_TIMETAMP_SECONDS, new org.apache.thrift.meta_data.FieldMetaData("lastAttemptedStoreTimetampSeconds", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I64)));
    tmpMap.put(_Fields.LAST_CACHE_HIT_TIMESTAMP_SECONDS, new org.apache.thrift.meta_data.FieldMetaData("lastCacheHitTimestampSeconds", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I64)));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(RuleKeyStoreLogEntry.class, metaDataMap);
  }

  public RuleKeyStoreLogEntry() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public RuleKeyStoreLogEntry(RuleKeyStoreLogEntry other) {
    __isset_bitfield = other.__isset_bitfield;
    if (other.isSetStoreId()) {
      this.storeId = other.storeId;
    }
    this.slaSeconds = other.slaSeconds;
    this.lastStoredTimestampSeconds = other.lastStoredTimestampSeconds;
    this.lastAttemptedStoreTimetampSeconds = other.lastAttemptedStoreTimetampSeconds;
    this.lastCacheHitTimestampSeconds = other.lastCacheHitTimestampSeconds;
  }

  public RuleKeyStoreLogEntry deepCopy() {
    return new RuleKeyStoreLogEntry(this);
  }

  @Override
  public void clear() {
    this.storeId = null;
    setSlaSecondsIsSet(false);
    this.slaSeconds = 0;
    setLastStoredTimestampSecondsIsSet(false);
    this.lastStoredTimestampSeconds = 0;
    setLastAttemptedStoreTimetampSecondsIsSet(false);
    this.lastAttemptedStoreTimetampSeconds = 0;
    setLastCacheHitTimestampSecondsIsSet(false);
    this.lastCacheHitTimestampSeconds = 0;
  }

  public java.lang.String getStoreId() {
    return this.storeId;
  }

  public RuleKeyStoreLogEntry setStoreId(java.lang.String storeId) {
    this.storeId = storeId;
    return this;
  }

  public void unsetStoreId() {
    this.storeId = null;
  }

  /** Returns true if field storeId is set (has been assigned a value) and false otherwise */
  public boolean isSetStoreId() {
    return this.storeId != null;
  }

  public void setStoreIdIsSet(boolean value) {
    if (!value) {
      this.storeId = null;
    }
  }

  public long getSlaSeconds() {
    return this.slaSeconds;
  }

  public RuleKeyStoreLogEntry setSlaSeconds(long slaSeconds) {
    this.slaSeconds = slaSeconds;
    setSlaSecondsIsSet(true);
    return this;
  }

  public void unsetSlaSeconds() {
    __isset_bitfield = org.apache.thrift.EncodingUtils.clearBit(__isset_bitfield, __SLASECONDS_ISSET_ID);
  }

  /** Returns true if field slaSeconds is set (has been assigned a value) and false otherwise */
  public boolean isSetSlaSeconds() {
    return org.apache.thrift.EncodingUtils.testBit(__isset_bitfield, __SLASECONDS_ISSET_ID);
  }

  public void setSlaSecondsIsSet(boolean value) {
    __isset_bitfield = org.apache.thrift.EncodingUtils.setBit(__isset_bitfield, __SLASECONDS_ISSET_ID, value);
  }

  public long getLastStoredTimestampSeconds() {
    return this.lastStoredTimestampSeconds;
  }

  public RuleKeyStoreLogEntry setLastStoredTimestampSeconds(long lastStoredTimestampSeconds) {
    this.lastStoredTimestampSeconds = lastStoredTimestampSeconds;
    setLastStoredTimestampSecondsIsSet(true);
    return this;
  }

  public void unsetLastStoredTimestampSeconds() {
    __isset_bitfield = org.apache.thrift.EncodingUtils.clearBit(__isset_bitfield, __LASTSTOREDTIMESTAMPSECONDS_ISSET_ID);
  }

  /** Returns true if field lastStoredTimestampSeconds is set (has been assigned a value) and false otherwise */
  public boolean isSetLastStoredTimestampSeconds() {
    return org.apache.thrift.EncodingUtils.testBit(__isset_bitfield, __LASTSTOREDTIMESTAMPSECONDS_ISSET_ID);
  }

  public void setLastStoredTimestampSecondsIsSet(boolean value) {
    __isset_bitfield = org.apache.thrift.EncodingUtils.setBit(__isset_bitfield, __LASTSTOREDTIMESTAMPSECONDS_ISSET_ID, value);
  }

  public long getLastAttemptedStoreTimetampSeconds() {
    return this.lastAttemptedStoreTimetampSeconds;
  }

  public RuleKeyStoreLogEntry setLastAttemptedStoreTimetampSeconds(long lastAttemptedStoreTimetampSeconds) {
    this.lastAttemptedStoreTimetampSeconds = lastAttemptedStoreTimetampSeconds;
    setLastAttemptedStoreTimetampSecondsIsSet(true);
    return this;
  }

  public void unsetLastAttemptedStoreTimetampSeconds() {
    __isset_bitfield = org.apache.thrift.EncodingUtils.clearBit(__isset_bitfield, __LASTATTEMPTEDSTORETIMETAMPSECONDS_ISSET_ID);
  }

  /** Returns true if field lastAttemptedStoreTimetampSeconds is set (has been assigned a value) and false otherwise */
  public boolean isSetLastAttemptedStoreTimetampSeconds() {
    return org.apache.thrift.EncodingUtils.testBit(__isset_bitfield, __LASTATTEMPTEDSTORETIMETAMPSECONDS_ISSET_ID);
  }

  public void setLastAttemptedStoreTimetampSecondsIsSet(boolean value) {
    __isset_bitfield = org.apache.thrift.EncodingUtils.setBit(__isset_bitfield, __LASTATTEMPTEDSTORETIMETAMPSECONDS_ISSET_ID, value);
  }

  public long getLastCacheHitTimestampSeconds() {
    return this.lastCacheHitTimestampSeconds;
  }

  public RuleKeyStoreLogEntry setLastCacheHitTimestampSeconds(long lastCacheHitTimestampSeconds) {
    this.lastCacheHitTimestampSeconds = lastCacheHitTimestampSeconds;
    setLastCacheHitTimestampSecondsIsSet(true);
    return this;
  }

  public void unsetLastCacheHitTimestampSeconds() {
    __isset_bitfield = org.apache.thrift.EncodingUtils.clearBit(__isset_bitfield, __LASTCACHEHITTIMESTAMPSECONDS_ISSET_ID);
  }

  /** Returns true if field lastCacheHitTimestampSeconds is set (has been assigned a value) and false otherwise */
  public boolean isSetLastCacheHitTimestampSeconds() {
    return org.apache.thrift.EncodingUtils.testBit(__isset_bitfield, __LASTCACHEHITTIMESTAMPSECONDS_ISSET_ID);
  }

  public void setLastCacheHitTimestampSecondsIsSet(boolean value) {
    __isset_bitfield = org.apache.thrift.EncodingUtils.setBit(__isset_bitfield, __LASTCACHEHITTIMESTAMPSECONDS_ISSET_ID, value);
  }

  public void setFieldValue(_Fields field, java.lang.Object value) {
    switch (field) {
    case STORE_ID:
      if (value == null) {
        unsetStoreId();
      } else {
        setStoreId((java.lang.String)value);
      }
      break;

    case SLA_SECONDS:
      if (value == null) {
        unsetSlaSeconds();
      } else {
        setSlaSeconds((java.lang.Long)value);
      }
      break;

    case LAST_STORED_TIMESTAMP_SECONDS:
      if (value == null) {
        unsetLastStoredTimestampSeconds();
      } else {
        setLastStoredTimestampSeconds((java.lang.Long)value);
      }
      break;

    case LAST_ATTEMPTED_STORE_TIMETAMP_SECONDS:
      if (value == null) {
        unsetLastAttemptedStoreTimetampSeconds();
      } else {
        setLastAttemptedStoreTimetampSeconds((java.lang.Long)value);
      }
      break;

    case LAST_CACHE_HIT_TIMESTAMP_SECONDS:
      if (value == null) {
        unsetLastCacheHitTimestampSeconds();
      } else {
        setLastCacheHitTimestampSeconds((java.lang.Long)value);
      }
      break;

    }
  }

  public java.lang.Object getFieldValue(_Fields field) {
    switch (field) {
    case STORE_ID:
      return getStoreId();

    case SLA_SECONDS:
      return getSlaSeconds();

    case LAST_STORED_TIMESTAMP_SECONDS:
      return getLastStoredTimestampSeconds();

    case LAST_ATTEMPTED_STORE_TIMETAMP_SECONDS:
      return getLastAttemptedStoreTimetampSeconds();

    case LAST_CACHE_HIT_TIMESTAMP_SECONDS:
      return getLastCacheHitTimestampSeconds();

    }
    throw new java.lang.IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new java.lang.IllegalArgumentException();
    }

    switch (field) {
    case STORE_ID:
      return isSetStoreId();
    case SLA_SECONDS:
      return isSetSlaSeconds();
    case LAST_STORED_TIMESTAMP_SECONDS:
      return isSetLastStoredTimestampSeconds();
    case LAST_ATTEMPTED_STORE_TIMETAMP_SECONDS:
      return isSetLastAttemptedStoreTimetampSeconds();
    case LAST_CACHE_HIT_TIMESTAMP_SECONDS:
      return isSetLastCacheHitTimestampSeconds();
    }
    throw new java.lang.IllegalStateException();
  }

  @Override
  public boolean equals(java.lang.Object that) {
    if (that == null)
      return false;
    if (that instanceof RuleKeyStoreLogEntry)
      return this.equals((RuleKeyStoreLogEntry)that);
    return false;
  }

  public boolean equals(RuleKeyStoreLogEntry that) {
    if (that == null)
      return false;
    if (this == that)
      return true;

    boolean this_present_storeId = true && this.isSetStoreId();
    boolean that_present_storeId = true && that.isSetStoreId();
    if (this_present_storeId || that_present_storeId) {
      if (!(this_present_storeId && that_present_storeId))
        return false;
      if (!this.storeId.equals(that.storeId))
        return false;
    }

    boolean this_present_slaSeconds = true && this.isSetSlaSeconds();
    boolean that_present_slaSeconds = true && that.isSetSlaSeconds();
    if (this_present_slaSeconds || that_present_slaSeconds) {
      if (!(this_present_slaSeconds && that_present_slaSeconds))
        return false;
      if (this.slaSeconds != that.slaSeconds)
        return false;
    }

    boolean this_present_lastStoredTimestampSeconds = true && this.isSetLastStoredTimestampSeconds();
    boolean that_present_lastStoredTimestampSeconds = true && that.isSetLastStoredTimestampSeconds();
    if (this_present_lastStoredTimestampSeconds || that_present_lastStoredTimestampSeconds) {
      if (!(this_present_lastStoredTimestampSeconds && that_present_lastStoredTimestampSeconds))
        return false;
      if (this.lastStoredTimestampSeconds != that.lastStoredTimestampSeconds)
        return false;
    }

    boolean this_present_lastAttemptedStoreTimetampSeconds = true && this.isSetLastAttemptedStoreTimetampSeconds();
    boolean that_present_lastAttemptedStoreTimetampSeconds = true && that.isSetLastAttemptedStoreTimetampSeconds();
    if (this_present_lastAttemptedStoreTimetampSeconds || that_present_lastAttemptedStoreTimetampSeconds) {
      if (!(this_present_lastAttemptedStoreTimetampSeconds && that_present_lastAttemptedStoreTimetampSeconds))
        return false;
      if (this.lastAttemptedStoreTimetampSeconds != that.lastAttemptedStoreTimetampSeconds)
        return false;
    }

    boolean this_present_lastCacheHitTimestampSeconds = true && this.isSetLastCacheHitTimestampSeconds();
    boolean that_present_lastCacheHitTimestampSeconds = true && that.isSetLastCacheHitTimestampSeconds();
    if (this_present_lastCacheHitTimestampSeconds || that_present_lastCacheHitTimestampSeconds) {
      if (!(this_present_lastCacheHitTimestampSeconds && that_present_lastCacheHitTimestampSeconds))
        return false;
      if (this.lastCacheHitTimestampSeconds != that.lastCacheHitTimestampSeconds)
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int hashCode = 1;

    hashCode = hashCode * 8191 + ((isSetStoreId()) ? 131071 : 524287);
    if (isSetStoreId())
      hashCode = hashCode * 8191 + storeId.hashCode();

    hashCode = hashCode * 8191 + ((isSetSlaSeconds()) ? 131071 : 524287);
    if (isSetSlaSeconds())
      hashCode = hashCode * 8191 + org.apache.thrift.TBaseHelper.hashCode(slaSeconds);

    hashCode = hashCode * 8191 + ((isSetLastStoredTimestampSeconds()) ? 131071 : 524287);
    if (isSetLastStoredTimestampSeconds())
      hashCode = hashCode * 8191 + org.apache.thrift.TBaseHelper.hashCode(lastStoredTimestampSeconds);

    hashCode = hashCode * 8191 + ((isSetLastAttemptedStoreTimetampSeconds()) ? 131071 : 524287);
    if (isSetLastAttemptedStoreTimetampSeconds())
      hashCode = hashCode * 8191 + org.apache.thrift.TBaseHelper.hashCode(lastAttemptedStoreTimetampSeconds);

    hashCode = hashCode * 8191 + ((isSetLastCacheHitTimestampSeconds()) ? 131071 : 524287);
    if (isSetLastCacheHitTimestampSeconds())
      hashCode = hashCode * 8191 + org.apache.thrift.TBaseHelper.hashCode(lastCacheHitTimestampSeconds);

    return hashCode;
  }

  @Override
  public int compareTo(RuleKeyStoreLogEntry other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = java.lang.Boolean.valueOf(isSetStoreId()).compareTo(other.isSetStoreId());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetStoreId()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.storeId, other.storeId);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = java.lang.Boolean.valueOf(isSetSlaSeconds()).compareTo(other.isSetSlaSeconds());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetSlaSeconds()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.slaSeconds, other.slaSeconds);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = java.lang.Boolean.valueOf(isSetLastStoredTimestampSeconds()).compareTo(other.isSetLastStoredTimestampSeconds());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetLastStoredTimestampSeconds()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.lastStoredTimestampSeconds, other.lastStoredTimestampSeconds);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = java.lang.Boolean.valueOf(isSetLastAttemptedStoreTimetampSeconds()).compareTo(other.isSetLastAttemptedStoreTimetampSeconds());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetLastAttemptedStoreTimetampSeconds()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.lastAttemptedStoreTimetampSeconds, other.lastAttemptedStoreTimetampSeconds);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = java.lang.Boolean.valueOf(isSetLastCacheHitTimestampSeconds()).compareTo(other.isSetLastCacheHitTimestampSeconds());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetLastCacheHitTimestampSeconds()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.lastCacheHitTimestampSeconds, other.lastCacheHitTimestampSeconds);
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
    java.lang.StringBuilder sb = new java.lang.StringBuilder("RuleKeyStoreLogEntry(");
    boolean first = true;

    if (isSetStoreId()) {
      sb.append("storeId:");
      if (this.storeId == null) {
        sb.append("null");
      } else {
        sb.append(this.storeId);
      }
      first = false;
    }
    if (isSetSlaSeconds()) {
      if (!first) sb.append(", ");
      sb.append("slaSeconds:");
      sb.append(this.slaSeconds);
      first = false;
    }
    if (isSetLastStoredTimestampSeconds()) {
      if (!first) sb.append(", ");
      sb.append("lastStoredTimestampSeconds:");
      sb.append(this.lastStoredTimestampSeconds);
      first = false;
    }
    if (isSetLastAttemptedStoreTimetampSeconds()) {
      if (!first) sb.append(", ");
      sb.append("lastAttemptedStoreTimetampSeconds:");
      sb.append(this.lastAttemptedStoreTimetampSeconds);
      first = false;
    }
    if (isSetLastCacheHitTimestampSeconds()) {
      if (!first) sb.append(", ");
      sb.append("lastCacheHitTimestampSeconds:");
      sb.append(this.lastCacheHitTimestampSeconds);
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
      // it doesn't seem like you should have to do this, but java serialization is wacky, and doesn't call the default constructor.
      __isset_bitfield = 0;
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class RuleKeyStoreLogEntryStandardSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public RuleKeyStoreLogEntryStandardScheme getScheme() {
      return new RuleKeyStoreLogEntryStandardScheme();
    }
  }

  private static class RuleKeyStoreLogEntryStandardScheme extends org.apache.thrift.scheme.StandardScheme<RuleKeyStoreLogEntry> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, RuleKeyStoreLogEntry struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // STORE_ID
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.storeId = iprot.readString();
              struct.setStoreIdIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // SLA_SECONDS
            if (schemeField.type == org.apache.thrift.protocol.TType.I64) {
              struct.slaSeconds = iprot.readI64();
              struct.setSlaSecondsIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // LAST_STORED_TIMESTAMP_SECONDS
            if (schemeField.type == org.apache.thrift.protocol.TType.I64) {
              struct.lastStoredTimestampSeconds = iprot.readI64();
              struct.setLastStoredTimestampSecondsIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 4: // LAST_ATTEMPTED_STORE_TIMETAMP_SECONDS
            if (schemeField.type == org.apache.thrift.protocol.TType.I64) {
              struct.lastAttemptedStoreTimetampSeconds = iprot.readI64();
              struct.setLastAttemptedStoreTimetampSecondsIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 5: // LAST_CACHE_HIT_TIMESTAMP_SECONDS
            if (schemeField.type == org.apache.thrift.protocol.TType.I64) {
              struct.lastCacheHitTimestampSeconds = iprot.readI64();
              struct.setLastCacheHitTimestampSecondsIsSet(true);
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

    public void write(org.apache.thrift.protocol.TProtocol oprot, RuleKeyStoreLogEntry struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.storeId != null) {
        if (struct.isSetStoreId()) {
          oprot.writeFieldBegin(STORE_ID_FIELD_DESC);
          oprot.writeString(struct.storeId);
          oprot.writeFieldEnd();
        }
      }
      if (struct.isSetSlaSeconds()) {
        oprot.writeFieldBegin(SLA_SECONDS_FIELD_DESC);
        oprot.writeI64(struct.slaSeconds);
        oprot.writeFieldEnd();
      }
      if (struct.isSetLastStoredTimestampSeconds()) {
        oprot.writeFieldBegin(LAST_STORED_TIMESTAMP_SECONDS_FIELD_DESC);
        oprot.writeI64(struct.lastStoredTimestampSeconds);
        oprot.writeFieldEnd();
      }
      if (struct.isSetLastAttemptedStoreTimetampSeconds()) {
        oprot.writeFieldBegin(LAST_ATTEMPTED_STORE_TIMETAMP_SECONDS_FIELD_DESC);
        oprot.writeI64(struct.lastAttemptedStoreTimetampSeconds);
        oprot.writeFieldEnd();
      }
      if (struct.isSetLastCacheHitTimestampSeconds()) {
        oprot.writeFieldBegin(LAST_CACHE_HIT_TIMESTAMP_SECONDS_FIELD_DESC);
        oprot.writeI64(struct.lastCacheHitTimestampSeconds);
        oprot.writeFieldEnd();
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class RuleKeyStoreLogEntryTupleSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public RuleKeyStoreLogEntryTupleScheme getScheme() {
      return new RuleKeyStoreLogEntryTupleScheme();
    }
  }

  private static class RuleKeyStoreLogEntryTupleScheme extends org.apache.thrift.scheme.TupleScheme<RuleKeyStoreLogEntry> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, RuleKeyStoreLogEntry struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol oprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet optionals = new java.util.BitSet();
      if (struct.isSetStoreId()) {
        optionals.set(0);
      }
      if (struct.isSetSlaSeconds()) {
        optionals.set(1);
      }
      if (struct.isSetLastStoredTimestampSeconds()) {
        optionals.set(2);
      }
      if (struct.isSetLastAttemptedStoreTimetampSeconds()) {
        optionals.set(3);
      }
      if (struct.isSetLastCacheHitTimestampSeconds()) {
        optionals.set(4);
      }
      oprot.writeBitSet(optionals, 5);
      if (struct.isSetStoreId()) {
        oprot.writeString(struct.storeId);
      }
      if (struct.isSetSlaSeconds()) {
        oprot.writeI64(struct.slaSeconds);
      }
      if (struct.isSetLastStoredTimestampSeconds()) {
        oprot.writeI64(struct.lastStoredTimestampSeconds);
      }
      if (struct.isSetLastAttemptedStoreTimetampSeconds()) {
        oprot.writeI64(struct.lastAttemptedStoreTimetampSeconds);
      }
      if (struct.isSetLastCacheHitTimestampSeconds()) {
        oprot.writeI64(struct.lastCacheHitTimestampSeconds);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, RuleKeyStoreLogEntry struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol iprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet incoming = iprot.readBitSet(5);
      if (incoming.get(0)) {
        struct.storeId = iprot.readString();
        struct.setStoreIdIsSet(true);
      }
      if (incoming.get(1)) {
        struct.slaSeconds = iprot.readI64();
        struct.setSlaSecondsIsSet(true);
      }
      if (incoming.get(2)) {
        struct.lastStoredTimestampSeconds = iprot.readI64();
        struct.setLastStoredTimestampSecondsIsSet(true);
      }
      if (incoming.get(3)) {
        struct.lastAttemptedStoreTimetampSeconds = iprot.readI64();
        struct.setLastAttemptedStoreTimetampSecondsIsSet(true);
      }
      if (incoming.get(4)) {
        struct.lastCacheHitTimestampSeconds = iprot.readI64();
        struct.setLastCacheHitTimestampSecondsIsSet(true);
      }
    }
  }

  private static <S extends org.apache.thrift.scheme.IScheme> S scheme(org.apache.thrift.protocol.TProtocol proto) {
    return (org.apache.thrift.scheme.StandardScheme.class.equals(proto.getScheme()) ? STANDARD_SCHEME_FACTORY : TUPLE_SCHEME_FACTORY).getScheme();
  }
}

