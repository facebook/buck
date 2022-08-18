// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: cd/resources/proto/kotlincd.proto

package com.facebook.buck.cd.model.kotlin;

/**
 * Protobuf type {@code kotlincd.api.v1.BuildKotlinCommand}
 */
@javax.annotation.Generated(value="protoc", comments="annotations:BuildKotlinCommand.java.pb.meta")
public  final class BuildKotlinCommand extends
    com.google.protobuf.GeneratedMessageV3 implements
    // @@protoc_insertion_point(message_implements:kotlincd.api.v1.BuildKotlinCommand)
    BuildKotlinCommandOrBuilder {
private static final long serialVersionUID = 0L;
  // Use BuildKotlinCommand.newBuilder() to construct.
  private BuildKotlinCommand(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
    super(builder);
  }
  private BuildKotlinCommand() {
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private BuildKotlinCommand(
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
          case 10: {
            com.facebook.buck.cd.model.kotlin.BaseCommandParams.Builder subBuilder = null;
            if (baseCommandParams_ != null) {
              subBuilder = baseCommandParams_.toBuilder();
            }
            baseCommandParams_ = input.readMessage(com.facebook.buck.cd.model.kotlin.BaseCommandParams.parser(), extensionRegistry);
            if (subBuilder != null) {
              subBuilder.mergeFrom(baseCommandParams_);
              baseCommandParams_ = subBuilder.buildPartial();
            }

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
    return com.facebook.buck.cd.model.kotlin.KotlinCDProto.internal_static_kotlincd_api_v1_BuildKotlinCommand_descriptor;
  }

  @java.lang.Override
  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return com.facebook.buck.cd.model.kotlin.KotlinCDProto.internal_static_kotlincd_api_v1_BuildKotlinCommand_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            com.facebook.buck.cd.model.kotlin.BuildKotlinCommand.class, com.facebook.buck.cd.model.kotlin.BuildKotlinCommand.Builder.class);
  }

  public static final int BASECOMMANDPARAMS_FIELD_NUMBER = 1;
  private com.facebook.buck.cd.model.kotlin.BaseCommandParams baseCommandParams_;
  /**
   * <code>.kotlincd.api.v1.BaseCommandParams baseCommandParams = 1;</code>
   */
  public boolean hasBaseCommandParams() {
    return baseCommandParams_ != null;
  }
  /**
   * <code>.kotlincd.api.v1.BaseCommandParams baseCommandParams = 1;</code>
   */
  public com.facebook.buck.cd.model.kotlin.BaseCommandParams getBaseCommandParams() {
    return baseCommandParams_ == null ? com.facebook.buck.cd.model.kotlin.BaseCommandParams.getDefaultInstance() : baseCommandParams_;
  }
  /**
   * <code>.kotlincd.api.v1.BaseCommandParams baseCommandParams = 1;</code>
   */
  public com.facebook.buck.cd.model.kotlin.BaseCommandParamsOrBuilder getBaseCommandParamsOrBuilder() {
    return getBaseCommandParams();
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
    if (baseCommandParams_ != null) {
      output.writeMessage(1, getBaseCommandParams());
    }
    unknownFields.writeTo(output);
  }

  @java.lang.Override
  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    if (baseCommandParams_ != null) {
      size += com.google.protobuf.CodedOutputStream
        .computeMessageSize(1, getBaseCommandParams());
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
    if (!(obj instanceof com.facebook.buck.cd.model.kotlin.BuildKotlinCommand)) {
      return super.equals(obj);
    }
    com.facebook.buck.cd.model.kotlin.BuildKotlinCommand other = (com.facebook.buck.cd.model.kotlin.BuildKotlinCommand) obj;

    if (hasBaseCommandParams() != other.hasBaseCommandParams()) return false;
    if (hasBaseCommandParams()) {
      if (!getBaseCommandParams()
          .equals(other.getBaseCommandParams())) return false;
    }
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
    if (hasBaseCommandParams()) {
      hash = (37 * hash) + BASECOMMANDPARAMS_FIELD_NUMBER;
      hash = (53 * hash) + getBaseCommandParams().hashCode();
    }
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static com.facebook.buck.cd.model.kotlin.BuildKotlinCommand parseFrom(
      java.nio.ByteBuffer data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static com.facebook.buck.cd.model.kotlin.BuildKotlinCommand parseFrom(
      java.nio.ByteBuffer data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static com.facebook.buck.cd.model.kotlin.BuildKotlinCommand parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static com.facebook.buck.cd.model.kotlin.BuildKotlinCommand parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static com.facebook.buck.cd.model.kotlin.BuildKotlinCommand parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static com.facebook.buck.cd.model.kotlin.BuildKotlinCommand parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static com.facebook.buck.cd.model.kotlin.BuildKotlinCommand parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static com.facebook.buck.cd.model.kotlin.BuildKotlinCommand parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static com.facebook.buck.cd.model.kotlin.BuildKotlinCommand parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static com.facebook.buck.cd.model.kotlin.BuildKotlinCommand parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static com.facebook.buck.cd.model.kotlin.BuildKotlinCommand parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static com.facebook.buck.cd.model.kotlin.BuildKotlinCommand parseFrom(
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
  public static Builder newBuilder(com.facebook.buck.cd.model.kotlin.BuildKotlinCommand prototype) {
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
   * Protobuf type {@code kotlincd.api.v1.BuildKotlinCommand}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
      // @@protoc_insertion_point(builder_implements:kotlincd.api.v1.BuildKotlinCommand)
      com.facebook.buck.cd.model.kotlin.BuildKotlinCommandOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return com.facebook.buck.cd.model.kotlin.KotlinCDProto.internal_static_kotlincd_api_v1_BuildKotlinCommand_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return com.facebook.buck.cd.model.kotlin.KotlinCDProto.internal_static_kotlincd_api_v1_BuildKotlinCommand_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              com.facebook.buck.cd.model.kotlin.BuildKotlinCommand.class, com.facebook.buck.cd.model.kotlin.BuildKotlinCommand.Builder.class);
    }

    // Construct using com.facebook.buck.cd.model.kotlin.BuildKotlinCommand.newBuilder()
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
      if (baseCommandParamsBuilder_ == null) {
        baseCommandParams_ = null;
      } else {
        baseCommandParams_ = null;
        baseCommandParamsBuilder_ = null;
      }
      return this;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return com.facebook.buck.cd.model.kotlin.KotlinCDProto.internal_static_kotlincd_api_v1_BuildKotlinCommand_descriptor;
    }

    @java.lang.Override
    public com.facebook.buck.cd.model.kotlin.BuildKotlinCommand getDefaultInstanceForType() {
      return com.facebook.buck.cd.model.kotlin.BuildKotlinCommand.getDefaultInstance();
    }

    @java.lang.Override
    public com.facebook.buck.cd.model.kotlin.BuildKotlinCommand build() {
      com.facebook.buck.cd.model.kotlin.BuildKotlinCommand result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    @java.lang.Override
    public com.facebook.buck.cd.model.kotlin.BuildKotlinCommand buildPartial() {
      com.facebook.buck.cd.model.kotlin.BuildKotlinCommand result = new com.facebook.buck.cd.model.kotlin.BuildKotlinCommand(this);
      if (baseCommandParamsBuilder_ == null) {
        result.baseCommandParams_ = baseCommandParams_;
      } else {
        result.baseCommandParams_ = baseCommandParamsBuilder_.build();
      }
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
      if (other instanceof com.facebook.buck.cd.model.kotlin.BuildKotlinCommand) {
        return mergeFrom((com.facebook.buck.cd.model.kotlin.BuildKotlinCommand)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(com.facebook.buck.cd.model.kotlin.BuildKotlinCommand other) {
      if (other == com.facebook.buck.cd.model.kotlin.BuildKotlinCommand.getDefaultInstance()) return this;
      if (other.hasBaseCommandParams()) {
        mergeBaseCommandParams(other.getBaseCommandParams());
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
      com.facebook.buck.cd.model.kotlin.BuildKotlinCommand parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (com.facebook.buck.cd.model.kotlin.BuildKotlinCommand) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }

    private com.facebook.buck.cd.model.kotlin.BaseCommandParams baseCommandParams_;
    private com.google.protobuf.SingleFieldBuilderV3<
        com.facebook.buck.cd.model.kotlin.BaseCommandParams, com.facebook.buck.cd.model.kotlin.BaseCommandParams.Builder, com.facebook.buck.cd.model.kotlin.BaseCommandParamsOrBuilder> baseCommandParamsBuilder_;
    /**
     * <code>.kotlincd.api.v1.BaseCommandParams baseCommandParams = 1;</code>
     */
    public boolean hasBaseCommandParams() {
      return baseCommandParamsBuilder_ != null || baseCommandParams_ != null;
    }
    /**
     * <code>.kotlincd.api.v1.BaseCommandParams baseCommandParams = 1;</code>
     */
    public com.facebook.buck.cd.model.kotlin.BaseCommandParams getBaseCommandParams() {
      if (baseCommandParamsBuilder_ == null) {
        return baseCommandParams_ == null ? com.facebook.buck.cd.model.kotlin.BaseCommandParams.getDefaultInstance() : baseCommandParams_;
      } else {
        return baseCommandParamsBuilder_.getMessage();
      }
    }
    /**
     * <code>.kotlincd.api.v1.BaseCommandParams baseCommandParams = 1;</code>
     */
    public Builder setBaseCommandParams(com.facebook.buck.cd.model.kotlin.BaseCommandParams value) {
      if (baseCommandParamsBuilder_ == null) {
        if (value == null) {
          throw new NullPointerException();
        }
        baseCommandParams_ = value;
        onChanged();
      } else {
        baseCommandParamsBuilder_.setMessage(value);
      }

      return this;
    }
    /**
     * <code>.kotlincd.api.v1.BaseCommandParams baseCommandParams = 1;</code>
     */
    public Builder setBaseCommandParams(
        com.facebook.buck.cd.model.kotlin.BaseCommandParams.Builder builderForValue) {
      if (baseCommandParamsBuilder_ == null) {
        baseCommandParams_ = builderForValue.build();
        onChanged();
      } else {
        baseCommandParamsBuilder_.setMessage(builderForValue.build());
      }

      return this;
    }
    /**
     * <code>.kotlincd.api.v1.BaseCommandParams baseCommandParams = 1;</code>
     */
    public Builder mergeBaseCommandParams(com.facebook.buck.cd.model.kotlin.BaseCommandParams value) {
      if (baseCommandParamsBuilder_ == null) {
        if (baseCommandParams_ != null) {
          baseCommandParams_ =
            com.facebook.buck.cd.model.kotlin.BaseCommandParams.newBuilder(baseCommandParams_).mergeFrom(value).buildPartial();
        } else {
          baseCommandParams_ = value;
        }
        onChanged();
      } else {
        baseCommandParamsBuilder_.mergeFrom(value);
      }

      return this;
    }
    /**
     * <code>.kotlincd.api.v1.BaseCommandParams baseCommandParams = 1;</code>
     */
    public Builder clearBaseCommandParams() {
      if (baseCommandParamsBuilder_ == null) {
        baseCommandParams_ = null;
        onChanged();
      } else {
        baseCommandParams_ = null;
        baseCommandParamsBuilder_ = null;
      }

      return this;
    }
    /**
     * <code>.kotlincd.api.v1.BaseCommandParams baseCommandParams = 1;</code>
     */
    public com.facebook.buck.cd.model.kotlin.BaseCommandParams.Builder getBaseCommandParamsBuilder() {
      
      onChanged();
      return getBaseCommandParamsFieldBuilder().getBuilder();
    }
    /**
     * <code>.kotlincd.api.v1.BaseCommandParams baseCommandParams = 1;</code>
     */
    public com.facebook.buck.cd.model.kotlin.BaseCommandParamsOrBuilder getBaseCommandParamsOrBuilder() {
      if (baseCommandParamsBuilder_ != null) {
        return baseCommandParamsBuilder_.getMessageOrBuilder();
      } else {
        return baseCommandParams_ == null ?
            com.facebook.buck.cd.model.kotlin.BaseCommandParams.getDefaultInstance() : baseCommandParams_;
      }
    }
    /**
     * <code>.kotlincd.api.v1.BaseCommandParams baseCommandParams = 1;</code>
     */
    private com.google.protobuf.SingleFieldBuilderV3<
        com.facebook.buck.cd.model.kotlin.BaseCommandParams, com.facebook.buck.cd.model.kotlin.BaseCommandParams.Builder, com.facebook.buck.cd.model.kotlin.BaseCommandParamsOrBuilder> 
        getBaseCommandParamsFieldBuilder() {
      if (baseCommandParamsBuilder_ == null) {
        baseCommandParamsBuilder_ = new com.google.protobuf.SingleFieldBuilderV3<
            com.facebook.buck.cd.model.kotlin.BaseCommandParams, com.facebook.buck.cd.model.kotlin.BaseCommandParams.Builder, com.facebook.buck.cd.model.kotlin.BaseCommandParamsOrBuilder>(
                getBaseCommandParams(),
                getParentForChildren(),
                isClean());
        baseCommandParams_ = null;
      }
      return baseCommandParamsBuilder_;
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


    // @@protoc_insertion_point(builder_scope:kotlincd.api.v1.BuildKotlinCommand)
  }

  // @@protoc_insertion_point(class_scope:kotlincd.api.v1.BuildKotlinCommand)
  private static final com.facebook.buck.cd.model.kotlin.BuildKotlinCommand DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new com.facebook.buck.cd.model.kotlin.BuildKotlinCommand();
  }

  public static com.facebook.buck.cd.model.kotlin.BuildKotlinCommand getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final com.google.protobuf.Parser<BuildKotlinCommand>
      PARSER = new com.google.protobuf.AbstractParser<BuildKotlinCommand>() {
    @java.lang.Override
    public BuildKotlinCommand parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return new BuildKotlinCommand(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<BuildKotlinCommand> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<BuildKotlinCommand> getParserForType() {
    return PARSER;
  }

  @java.lang.Override
  public com.facebook.buck.cd.model.kotlin.BuildKotlinCommand getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

