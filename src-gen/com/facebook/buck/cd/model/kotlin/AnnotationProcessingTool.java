// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: cd/resources/proto/kotlincd.proto

package com.facebook.buck.cd.model.kotlin;

/**
 * <pre>
 ** Model for KotlinLibraryDescription.AnnotationProcessingTool 
 * </pre>
 *
 * Protobuf enum {@code kotlincd.api.v1.AnnotationProcessingTool}
 */
@javax.annotation.Generated(value="protoc", comments="annotations:AnnotationProcessingTool.java.pb.meta")
public enum AnnotationProcessingTool
    implements com.google.protobuf.ProtocolMessageEnum {
  /**
   * <code>KAPT = 0;</code>
   */
  KAPT(0),
  /**
   * <code>JAVAC = 1;</code>
   */
  JAVAC(1),
  UNRECOGNIZED(-1),
  ;

  /**
   * <code>KAPT = 0;</code>
   */
  public static final int KAPT_VALUE = 0;
  /**
   * <code>JAVAC = 1;</code>
   */
  public static final int JAVAC_VALUE = 1;


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
  public static AnnotationProcessingTool valueOf(int value) {
    return forNumber(value);
  }

  public static AnnotationProcessingTool forNumber(int value) {
    switch (value) {
      case 0: return KAPT;
      case 1: return JAVAC;
      default: return null;
    }
  }

  public static com.google.protobuf.Internal.EnumLiteMap<AnnotationProcessingTool>
      internalGetValueMap() {
    return internalValueMap;
  }
  private static final com.google.protobuf.Internal.EnumLiteMap<
      AnnotationProcessingTool> internalValueMap =
        new com.google.protobuf.Internal.EnumLiteMap<AnnotationProcessingTool>() {
          public AnnotationProcessingTool findValueByNumber(int number) {
            return AnnotationProcessingTool.forNumber(number);
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
    return com.facebook.buck.cd.model.kotlin.KotlinCDProto.getDescriptor().getEnumTypes().get(0);
  }

  private static final AnnotationProcessingTool[] VALUES = values();

  public static AnnotationProcessingTool valueOf(
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

  private AnnotationProcessingTool(int value) {
    this.value = value;
  }

  // @@protoc_insertion_point(enum_scope:kotlincd.api.v1.AnnotationProcessingTool)
}

