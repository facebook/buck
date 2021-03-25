// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: src/com/facebook/buck/javacd/resources/proto/javacd.proto

package com.facebook.buck.javacd.model;

@javax.annotation.Generated(value="protoc", comments="annotations:BasePipeliningCommandOrBuilder.java.pb.meta")
public interface BasePipeliningCommandOrBuilder extends
    // @@protoc_insertion_point(interface_extends:javacd.api.v1.BasePipeliningCommand)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>.javacd.api.v1.BuildTargetValue buildTargetValue = 1;</code>
   */
  boolean hasBuildTargetValue();
  /**
   * <code>.javacd.api.v1.BuildTargetValue buildTargetValue = 1;</code>
   */
  com.facebook.buck.javacd.model.BuildTargetValue getBuildTargetValue();
  /**
   * <code>.javacd.api.v1.BuildTargetValue buildTargetValue = 1;</code>
   */
  com.facebook.buck.javacd.model.BuildTargetValueOrBuilder getBuildTargetValueOrBuilder();

  /**
   * <code>.javacd.api.v1.FilesystemParams filesystemParams = 2;</code>
   */
  boolean hasFilesystemParams();
  /**
   * <code>.javacd.api.v1.FilesystemParams filesystemParams = 2;</code>
   */
  com.facebook.buck.javacd.model.FilesystemParams getFilesystemParams();
  /**
   * <code>.javacd.api.v1.FilesystemParams filesystemParams = 2;</code>
   */
  com.facebook.buck.javacd.model.FilesystemParamsOrBuilder getFilesystemParamsOrBuilder();

  /**
   * <code>.javacd.api.v1.OutputPathsValue outputPathsValue = 3;</code>
   */
  boolean hasOutputPathsValue();
  /**
   * <code>.javacd.api.v1.OutputPathsValue outputPathsValue = 3;</code>
   */
  com.facebook.buck.javacd.model.OutputPathsValue getOutputPathsValue();
  /**
   * <code>.javacd.api.v1.OutputPathsValue outputPathsValue = 3;</code>
   */
  com.facebook.buck.javacd.model.OutputPathsValueOrBuilder getOutputPathsValueOrBuilder();

  /**
   * <code>repeated .javacd.api.v1.RelPathMapEntry resourcesMap = 4;</code>
   */
  java.util.List<com.facebook.buck.javacd.model.RelPathMapEntry> 
      getResourcesMapList();
  /**
   * <code>repeated .javacd.api.v1.RelPathMapEntry resourcesMap = 4;</code>
   */
  com.facebook.buck.javacd.model.RelPathMapEntry getResourcesMap(int index);
  /**
   * <code>repeated .javacd.api.v1.RelPathMapEntry resourcesMap = 4;</code>
   */
  int getResourcesMapCount();
  /**
   * <code>repeated .javacd.api.v1.RelPathMapEntry resourcesMap = 4;</code>
   */
  java.util.List<? extends com.facebook.buck.javacd.model.RelPathMapEntryOrBuilder> 
      getResourcesMapOrBuilderList();
  /**
   * <code>repeated .javacd.api.v1.RelPathMapEntry resourcesMap = 4;</code>
   */
  com.facebook.buck.javacd.model.RelPathMapEntryOrBuilder getResourcesMapOrBuilder(
      int index);

  /**
   * <code>map&lt;string, .javacd.api.v1.RelPath&gt; cellToPathMappings = 5;</code>
   */
  int getCellToPathMappingsCount();
  /**
   * <code>map&lt;string, .javacd.api.v1.RelPath&gt; cellToPathMappings = 5;</code>
   */
  boolean containsCellToPathMappings(
      java.lang.String key);
  /**
   * Use {@link #getCellToPathMappingsMap()} instead.
   */
  @java.lang.Deprecated
  java.util.Map<java.lang.String, com.facebook.buck.javacd.model.RelPath>
  getCellToPathMappings();
  /**
   * <code>map&lt;string, .javacd.api.v1.RelPath&gt; cellToPathMappings = 5;</code>
   */
  java.util.Map<java.lang.String, com.facebook.buck.javacd.model.RelPath>
  getCellToPathMappingsMap();
  /**
   * <code>map&lt;string, .javacd.api.v1.RelPath&gt; cellToPathMappings = 5;</code>
   */

  com.facebook.buck.javacd.model.RelPath getCellToPathMappingsOrDefault(
      java.lang.String key,
      com.facebook.buck.javacd.model.RelPath defaultValue);
  /**
   * <code>map&lt;string, .javacd.api.v1.RelPath&gt; cellToPathMappings = 5;</code>
   */

  com.facebook.buck.javacd.model.RelPath getCellToPathMappingsOrThrow(
      java.lang.String key);
}
