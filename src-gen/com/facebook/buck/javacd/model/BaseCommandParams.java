// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: src/com/facebook/buck/javacd/resources/proto/javacd.proto

package com.facebook.buck.javacd.model;

/**
 * <pre>
 * Base parameters used by all commands (abi or library jars, as well as pipelining command)
 * </pre>
 *
 * Protobuf type {@code javacd.api.v1.BaseCommandParams}
 */
@javax.annotation.Generated(value="protoc", comments="annotations:BaseCommandParams.java.pb.meta")
public  final class BaseCommandParams extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:javacd.api.v1.BaseCommandParams)
    BaseCommandParamsOrBuilder {
private static final long serialVersionUID = 0L;
  // Use BaseCommandParams.newBuilder() to construct.
  private BaseCommandParams(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private BaseCommandParams() {
    spoolMode_ = 0;
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private BaseCommandParams(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    if (extensionRegistry == null) {
      throw new java.lang.NullPointerException();
    }
    int mutable_bitField0_ = 0;
    com.google.protobuf.UnknownFieldSet.Builder unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder();
    try {
      boolean done = false;
      while (!done) {
        int tag = input.readTag();
        switch (tag) {
          case 0:
            done = true;
            break;
          case 8: {
            int rawValue = input.readEnum();

            spoolMode_ = rawValue;
            break;
          }
          case 16: {

            hasAnnotationProcessing_ = input.readBool();
            break;
          }
          case 24: {

            withDownwardApi_ = input.readBool();
            break;
          }
          default: {
            if (!parseUnknownField(
                input, unknownFields, extensionRegistry, tag)) {
              done = true;
            }
            break;
          }
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw e.setUnfinishedMessage(this);
    } catch (java.io.IOException e) {
      throw new com.google.protobuf.InvalidProtocolBufferException(
          e).setUnfinishedMessage(this);
    } finally {
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return com.facebook.buck.javacd.model.JavaCDProto.internal_static_javacd_api_v1_BaseCommandParams_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return com.facebook.buck.javacd.model.JavaCDProto.internal_static_javacd_api_v1_BaseCommandParams_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            com.facebook.buck.javacd.model.BaseCommandParams.class, com.facebook.buck.javacd.model.BaseCommandParams.Builder.class);
  }

  /**
   * <pre>
   ** The method in which the compiler output is spooled. 
   * </pre>
   *
   * Protobuf enum {@code javacd.api.v1.BaseCommandParams.SpoolMode}
   */
  public enum SpoolMode
      implements com.google.protobuf.ProtocolMessageEnum {
    /**
     * <code>UNKNOWN = 0;</code>
     */
    UNKNOWN(0),
    /**
     * <pre>
     **
     * Writes the compiler output directly to a .jar file while retaining the intermediate .class
     * files in memory. If `postprocessClassesCommands` are present, the builder will resort to writing .class files to
     * disk by necessity.
     * </pre>
     *
     * <code>DIRECT_TO_JAR = 1;</code>
     */
    DIRECT_TO_JAR(1),
    /**
     * <pre>
     **
     * Writes the intermediate .class files from the compiler output to disk which is later packed
     * up into a .jar file.
     * </pre>
     *
     * <code>INTERMEDIATE_TO_DISK = 2;</code>
     */
    INTERMEDIATE_TO_DISK(2),
    UNRECOGNIZED(-1),
    ;

    /**
     * <code>UNKNOWN = 0;</code>
     */
    public static final int UNKNOWN_VALUE = 0;
    /**
     * <pre>
     **
     * Writes the compiler output directly to a .jar file while retaining the intermediate .class
     * files in memory. If `postprocessClassesCommands` are present, the builder will resort to writing .class files to
     * disk by necessity.
     * </pre>
     *
     * <code>DIRECT_TO_JAR = 1;</code>
     */
    public static final int DIRECT_TO_JAR_VALUE = 1;
    /**
     * <pre>
     **
     * Writes the intermediate .class files from the compiler output to disk which is later packed
     * up into a .jar file.
     * </pre>
     *
     * <code>INTERMEDIATE_TO_DISK = 2;</code>
     */
    public static final int INTERMEDIATE_TO_DISK_VALUE = 2;


    public final int getNumber() {
      if (this == UNRECOGNIZED) {
        throw new java.lang.IllegalArgumentException(
            "Can't get the number of an unknown enum value.");
      }
      return value;
    }

    /**
     * @deprecated Use {@link #forNumber(int)} instead.
     */
    @java.lang.Deprecated
    public static SpoolMode valueOf(int value) {
      return forNumber(value);
    }

    public static SpoolMode forNumber(int value) {
      switch (value) {
        case 0: return UNKNOWN;
        case 1: return DIRECT_TO_JAR;
        case 2: return INTERMEDIATE_TO_DISK;
        default: return null;
      }
    }

    public static com.google.protobuf.Internal.EnumLiteMap<SpoolMode>
        internalGetValueMap() {
      return internalValueMap;
    }
    private static final com.google.protobuf.Internal.EnumLiteMap<
        SpoolMode> internalValueMap =
          new com.google.protobuf.Internal.EnumLiteMap<SpoolMode>() {
            public SpoolMode findValueByNumber(int number) {
              return SpoolMode.forNumber(number);
            }
          };

    public final com.google.protobuf.Descriptors.EnumValueDescriptor
        getValueDescriptor() {
      return getDescriptor().getValues().get(ordinal());
    }
    public final com.google.protobuf.Descriptors.EnumDescriptor
        getDescriptorForType() {
      return getDescriptor();
    }
    public static final com.google.protobuf.Descriptors.EnumDescriptor
        getDescriptor() {
      return com.facebook.buck.javacd.model.BaseCommandParams.getDescriptor().getEnumTypes().get(0);
    }

    private static final SpoolMode[] VALUES = values();

    public static SpoolMode valueOf(
        com.google.protobuf.Descriptors.EnumValueDescriptor desc) {
      if (desc.getType() != getDescriptor()) {
        throw new java.lang.IllegalArgumentException(
          "EnumValueDescriptor is not for this type.");
      }
      if (desc.getIndex() == -1) {
        return UNRECOGNIZED;
      }
      return VALUES[desc.getIndex()];
    }

    private final int value;

    private SpoolMode(int value) {
      this.value = value;
    }

    // @@protoc_insertion_point(enum_scope:javacd.api.v1.BaseCommandParams.SpoolMode)
  }

  public static final int SPOOLMODE_FIELD_NUMBER = 1;
  private int spoolMode_;
  /**
   * <code>.javacd.api.v1.BaseCommandParams.SpoolMode spoolMode = 1;</code>
   */
  public int getSpoolModeValue() {
    return spoolMode_;
  }
  /**
   * <code>.javacd.api.v1.BaseCommandParams.SpoolMode spoolMode = 1;</code>
   */
  public com.facebook.buck.javacd.model.BaseCommandParams.SpoolMode getSpoolMode() {
    @SuppressWarnings("deprecation")
    com.facebook.buck.javacd.model.BaseCommandParams.SpoolMode result = com.facebook.buck.javacd.model.BaseCommandParams.SpoolMode.valueOf(spoolMode_);
    return result == null ? com.facebook.buck.javacd.model.BaseCommandParams.SpoolMode.UNRECOGNIZED : result;
  }

  public static final int HASANNOTATIONPROCESSING_FIELD_NUMBER = 2;
  private boolean hasAnnotationProcessing_;
  /**
   * <code>bool hasAnnotationProcessing = 2;</code>
   */
  public boolean getHasAnnotationProcessing() {
    return hasAnnotationProcessing_;
  }

  public static final int WITHDOWNWARDAPI_FIELD_NUMBER = 3;
  private boolean withDownwardApi_;
  /**
   * <code>bool withDownwardApi = 3;</code>
   */
  public boolean getWithDownwardApi() {
    return withDownwardApi_;
  }

  private byte memoizedIsInitialized = -1;
  @java.lang.Override
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    memoizedIsInitialized = 1;
    return true;
  }

  @java.lang.Override
  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    if (spoolMode_ != com.facebook.buck.javacd.model.BaseCommandParams.SpoolMode.UNKNOWN.getNumber()) {
      output.writeEnum(1, spoolMode_);
    }
    if (hasAnnotationProcessing_ != false) {
      output.writeBool(2, hasAnnotationProcessing_);
    }
    if (withDownwardApi_ != false) {
      output.writeBool(3, withDownwardApi_);
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (spoolMode_ != com.facebook.buck.javacd.model.BaseCommandParams.SpoolMode.UNKNOWN.getNumber()) {
      size += com.google.protobuf.CodedOutputStream
        .computeEnumSize(1, spoolMode_);
    }
    if (hasAnnotationProcessing_ != false) {
      size += com.google.protobuf.CodedOutputStream
        .computeBoolSize(2, hasAnnotationProcessing_);
    }
    if (withDownwardApi_ != false) {
      size += com.google.protobuf.CodedOutputStream
        .computeBoolSize(3, withDownwardApi_);
    }
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof com.facebook.buck.javacd.model.BaseCommandParams)) {
      return super.equals(obj);
    }
    com.facebook.buck.javacd.model.BaseCommandParams other = (com.facebook.buck.javacd.model.BaseCommandParams) obj;

    if (spoolMode_ != other.spoolMode_) return false;
    if (getHasAnnotationProcessing()
        != other.getHasAnnotationProcessing()) return false;
    if (getWithDownwardApi()
        != other.getWithDownwardApi()) return false;
    if (!unknownFields.equals(other.unknownFields)) return false;
    return true;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptor().hashCode();
    hash = (37 * hash) + SPOOLMODE_FIELD_NUMBER;
    hash = (53 * hash) + spoolMode_;
    hash = (37 * hash) + HASANNOTATIONPROCESSING_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashBoolean(
        getHasAnnotationProcessing());
    hash = (37 * hash) + WITHDOWNWARDAPI_FIELD_NUMBER;
    hash = (53 * hash) + com.google.protobuf.Internal.hashBoolean(
        getWithDownwardApi());
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static com.facebook.buck.javacd.model.BaseCommandParams parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static com.facebook.buck.javacd.model.BaseCommandParams parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static com.facebook.buck.javacd.model.BaseCommandParams parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static com.facebook.buck.javacd.model.BaseCommandParams parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static com.facebook.buck.javacd.model.BaseCommandParams parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static com.facebook.buck.javacd.model.BaseCommandParams parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static com.facebook.buck.javacd.model.BaseCommandParams parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static com.facebook.buck.javacd.model.BaseCommandParams parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static com.facebook.buck.javacd.model.BaseCommandParams parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static com.facebook.buck.javacd.model.BaseCommandParams parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static com.facebook.buck.javacd.model.BaseCommandParams parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static com.facebook.buck.javacd.model.BaseCommandParams parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  @java.lang.Override
  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(com.facebook.buck.javacd.model.BaseCommandParams prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  @java.lang.Override
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * <pre>
   * Base parameters used by all commands (abi or library jars, as well as pipelining command)
   * </pre>
   *
   * Protobuf type {@code javacd.api.v1.BaseCommandParams}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:javacd.api.v1.BaseCommandParams)
      com.facebook.buck.javacd.model.BaseCommandParamsOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return com.facebook.buck.javacd.model.JavaCDProto.internal_static_javacd_api_v1_BaseCommandParams_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return com.facebook.buck.javacd.model.JavaCDProto.internal_static_javacd_api_v1_BaseCommandParams_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              com.facebook.buck.javacd.model.BaseCommandParams.class, com.facebook.buck.javacd.model.BaseCommandParams.Builder.class);
    }

    // Construct using com.facebook.buck.javacd.model.BaseCommandParams.newBuilder()
    private Builder() {
      maybeForceBuilderInitialization();
    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);
      maybeForceBuilderInitialization();
    }
    private void maybeForceBuilderInitialization() {
      if (com.google.protobuf.GeneratedMessageV3
              .alwaysUseFieldBuilders) {
      }
    }
    @java.lang.Override
    public Builder clear() {
      super.clear();
      spoolMode_ = 0;

      hasAnnotationProcessing_ = false;

      withDownwardApi_ = false;

      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return com.facebook.buck.javacd.model.JavaCDProto.internal_static_javacd_api_v1_BaseCommandParams_descriptor;
    }

    @java.lang.Override
    public com.facebook.buck.javacd.model.BaseCommandParams getDefaultInstanceForType() {
      return com.facebook.buck.javacd.model.BaseCommandParams.getDefaultInstance();
    }

    @java.lang.Override
    public com.facebook.buck.javacd.model.BaseCommandParams build() {
      com.facebook.buck.javacd.model.BaseCommandParams result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public com.facebook.buck.javacd.model.BaseCommandParams buildPartial() {
      com.facebook.buck.javacd.model.BaseCommandParams result = new com.facebook.buck.javacd.model.BaseCommandParams(this);
      result.spoolMode_ = spoolMode_;
      result.hasAnnotationProcessing_ = hasAnnotationProcessing_;
      result.withDownwardApi_ = withDownwardApi_;
      onBuilt();
      return result;
    }

    @java.lang.Override
    public Builder clone() {
      return super.clone();
    }
    @java.lang.Override
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.setField(field, value);
    }
    @java.lang.Override
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return super.clearField(field);
    }
    @java.lang.Override
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return super.clearOneof(oneof);
    }
    @java.lang.Override
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, java.lang.Object value) {
      return super.setRepeatedField(field, index, value);
    }
    @java.lang.Override
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        java.lang.Object value) {
      return super.addRepeatedField(field, value);
    }
    @java.lang.Override
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof com.facebook.buck.javacd.model.BaseCommandParams) {
        return mergeFrom((com.facebook.buck.javacd.model.BaseCommandParams)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(com.facebook.buck.javacd.model.BaseCommandParams other) {
      if (other == com.facebook.buck.javacd.model.BaseCommandParams.getDefaultInstance()) return this;
      if (other.spoolMode_ != 0) {
        setSpoolModeValue(other.getSpoolModeValue());
      }
      if (other.getHasAnnotationProcessing() != false) {
        setHasAnnotationProcessing(other.getHasAnnotationProcessing());
      }
      if (other.getWithDownwardApi() != false) {
        setWithDownwardApi(other.getWithDownwardApi());
      }
      this.mergeUnknownFields(other.unknownFields);
      onChanged();
      return this;
    }

    @java.lang.Override
    public final boolean isInitialized() {
      return true;
    }

    @java.lang.Override
    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      com.facebook.buck.javacd.model.BaseCommandParams parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (com.facebook.buck.javacd.model.BaseCommandParams) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private int spoolMode_ = 0;
    /**
     * <code>.javacd.api.v1.BaseCommandParams.SpoolMode spoolMode = 1;</code>
     */
    public int getSpoolModeValue() {
      return spoolMode_;
    }
    /**
     * <code>.javacd.api.v1.BaseCommandParams.SpoolMode spoolMode = 1;</code>
     */
    public Builder setSpoolModeValue(int value) {
      spoolMode_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>.javacd.api.v1.BaseCommandParams.SpoolMode spoolMode = 1;</code>
     */
    public com.facebook.buck.javacd.model.BaseCommandParams.SpoolMode getSpoolMode() {
      @SuppressWarnings("deprecation")
      com.facebook.buck.javacd.model.BaseCommandParams.SpoolMode result = com.facebook.buck.javacd.model.BaseCommandParams.SpoolMode.valueOf(spoolMode_);
      return result == null ? com.facebook.buck.javacd.model.BaseCommandParams.SpoolMode.UNRECOGNIZED : result;
    }
    /**
     * <code>.javacd.api.v1.BaseCommandParams.SpoolMode spoolMode = 1;</code>
     */
    public Builder setSpoolMode(com.facebook.buck.javacd.model.BaseCommandParams.SpoolMode value) {
      if (value == null) {
        throw new NullPointerException();
      }
      
      spoolMode_ = value.getNumber();
      onChanged();
      return this;
    }
    /**
     * <code>.javacd.api.v1.BaseCommandParams.SpoolMode spoolMode = 1;</code>
     */
    public Builder clearSpoolMode() {
      
      spoolMode_ = 0;
      onChanged();
      return this;
    }

    private boolean hasAnnotationProcessing_ ;
    /**
     * <code>bool hasAnnotationProcessing = 2;</code>
     */
    public boolean getHasAnnotationProcessing() {
      return hasAnnotationProcessing_;
    }
    /**
     * <code>bool hasAnnotationProcessing = 2;</code>
     */
    public Builder setHasAnnotationProcessing(boolean value) {
      
      hasAnnotationProcessing_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>bool hasAnnotationProcessing = 2;</code>
     */
    public Builder clearHasAnnotationProcessing() {
      
      hasAnnotationProcessing_ = false;
      onChanged();
      return this;
    }

    private boolean withDownwardApi_ ;
    /**
     * <code>bool withDownwardApi = 3;</code>
     */
    public boolean getWithDownwardApi() {
      return withDownwardApi_;
    }
    /**
     * <code>bool withDownwardApi = 3;</code>
     */
    public Builder setWithDownwardApi(boolean value) {
      
      withDownwardApi_ = value;
      onChanged();
      return this;
    }
    /**
     * <code>bool withDownwardApi = 3;</code>
     */
    public Builder clearWithDownwardApi() {
      
      withDownwardApi_ = false;
      onChanged();
      return this;
    }
    @java.lang.Override
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFields(unknownFields);
    }

    @java.lang.Override
    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:javacd.api.v1.BaseCommandParams)
  }

  // @@protoc_insertion_point(class_scope:javacd.api.v1.BaseCommandParams)
  private static final com.facebook.buck.javacd.model.BaseCommandParams DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new com.facebook.buck.javacd.model.BaseCommandParams();
  }

  public static com.facebook.buck.javacd.model.BaseCommandParams getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<BaseCommandParams>
      PARSER = new com.google.protobuf.AbstractParser<BaseCommandParams>() {
    @java.lang.Override
    public BaseCommandParams parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new BaseCommandParams(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<BaseCommandParams> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<BaseCommandParams> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public com.facebook.buck.javacd.model.BaseCommandParams getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

