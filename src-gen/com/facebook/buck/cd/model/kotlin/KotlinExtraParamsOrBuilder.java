// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: cd/resources/proto/kotlincd.proto

package com.facebook.buck.cd.model.kotlin;

@javax.annotation.Generated(value="protoc", comments="annotations:KotlinExtraParamsOrBuilder.java.pb.meta")
public interface KotlinExtraParamsOrBuilder extends
    // @@protoc_insertion_point(interface_extends:kotlincd.api.v1.KotlinExtraParams)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>.AbsPath pathToKotlinc = 1;</code>
   */
  boolean hasPathToKotlinc();
  /**
   * <code>.AbsPath pathToKotlinc = 1;</code>
   */
  com.facebook.buck.cd.model.common.AbsPath getPathToKotlinc();
  /**
   * <code>.AbsPath pathToKotlinc = 1;</code>
   */
  com.facebook.buck.cd.model.common.AbsPathOrBuilder getPathToKotlincOrBuilder();

  /**
   * <code>repeated .AbsPath extraClassPaths = 2;</code>
   */
  java.util.List<com.facebook.buck.cd.model.common.AbsPath> 
      getExtraClassPathsList();
  /**
   * <code>repeated .AbsPath extraClassPaths = 2;</code>
   */
  com.facebook.buck.cd.model.common.AbsPath getExtraClassPaths(int index);
  /**
   * <code>repeated .AbsPath extraClassPaths = 2;</code>
   */
  int getExtraClassPathsCount();
  /**
   * <code>repeated .AbsPath extraClassPaths = 2;</code>
   */
  java.util.List<? extends com.facebook.buck.cd.model.common.AbsPathOrBuilder> 
      getExtraClassPathsOrBuilderList();
  /**
   * <code>repeated .AbsPath extraClassPaths = 2;</code>
   */
  com.facebook.buck.cd.model.common.AbsPathOrBuilder getExtraClassPathsOrBuilder(
      int index);

  /**
   * <code>.AbsPath standardLibraryClassPath = 3;</code>
   */
  boolean hasStandardLibraryClassPath();
  /**
   * <code>.AbsPath standardLibraryClassPath = 3;</code>
   */
  com.facebook.buck.cd.model.common.AbsPath getStandardLibraryClassPath();
  /**
   * <code>.AbsPath standardLibraryClassPath = 3;</code>
   */
  com.facebook.buck.cd.model.common.AbsPathOrBuilder getStandardLibraryClassPathOrBuilder();

  /**
   * <code>.AbsPath annotationProcessingClassPath = 4;</code>
   */
  boolean hasAnnotationProcessingClassPath();
  /**
   * <code>.AbsPath annotationProcessingClassPath = 4;</code>
   */
  com.facebook.buck.cd.model.common.AbsPath getAnnotationProcessingClassPath();
  /**
   * <code>.AbsPath annotationProcessingClassPath = 4;</code>
   */
  com.facebook.buck.cd.model.common.AbsPathOrBuilder getAnnotationProcessingClassPathOrBuilder();

  /**
   * <code>.kotlincd.api.v1.AnnotationProcessingTool annotationProcessingTool = 5;</code>
   */
  int getAnnotationProcessingToolValue();
  /**
   * <code>.kotlincd.api.v1.AnnotationProcessingTool annotationProcessingTool = 5;</code>
   */
  com.facebook.buck.cd.model.kotlin.AnnotationProcessingTool getAnnotationProcessingTool();

  /**
   * <code>repeated string extraKotlincArguments = 6;</code>
   */
  java.util.List<java.lang.String>
      getExtraKotlincArgumentsList();
  /**
   * <code>repeated string extraKotlincArguments = 6;</code>
   */
  int getExtraKotlincArgumentsCount();
  /**
   * <code>repeated string extraKotlincArguments = 6;</code>
   */
  java.lang.String getExtraKotlincArguments(int index);
  /**
   * <code>repeated string extraKotlincArguments = 6;</code>
   */
  com.google.protobuf.ByteString
      getExtraKotlincArgumentsBytes(int index);

  /**
   * <pre>
   * kotlinCompilerPlugin keys are AbsPaths encoded as strings due to limitations in proto3.
   * </pre>
   *
   * <code>map&lt;string, .kotlincd.api.v1.PluginParams&gt; kotlinCompilerPlugins = 7;</code>
   */
  int getKotlinCompilerPluginsCount();
  /**
   * <pre>
   * kotlinCompilerPlugin keys are AbsPaths encoded as strings due to limitations in proto3.
   * </pre>
   *
   * <code>map&lt;string, .kotlincd.api.v1.PluginParams&gt; kotlinCompilerPlugins = 7;</code>
   */
  boolean containsKotlinCompilerPlugins(
      java.lang.String key);
  /**
   * Use {@link #getKotlinCompilerPluginsMap()} instead.
   */
  @java.lang.Deprecated
  java.util.Map<java.lang.String, com.facebook.buck.cd.model.kotlin.PluginParams>
  getKotlinCompilerPlugins();
  /**
   * <pre>
   * kotlinCompilerPlugin keys are AbsPaths encoded as strings due to limitations in proto3.
   * </pre>
   *
   * <code>map&lt;string, .kotlincd.api.v1.PluginParams&gt; kotlinCompilerPlugins = 7;</code>
   */
  java.util.Map<java.lang.String, com.facebook.buck.cd.model.kotlin.PluginParams>
  getKotlinCompilerPluginsMap();
  /**
   * <pre>
   * kotlinCompilerPlugin keys are AbsPaths encoded as strings due to limitations in proto3.
   * </pre>
   *
   * <code>map&lt;string, .kotlincd.api.v1.PluginParams&gt; kotlinCompilerPlugins = 7;</code>
   */

  com.facebook.buck.cd.model.kotlin.PluginParams getKotlinCompilerPluginsOrDefault(
      java.lang.String key,
      com.facebook.buck.cd.model.kotlin.PluginParams defaultValue);
  /**
   * <pre>
   * kotlinCompilerPlugin keys are AbsPaths encoded as strings due to limitations in proto3.
   * </pre>
   *
   * <code>map&lt;string, .kotlincd.api.v1.PluginParams&gt; kotlinCompilerPlugins = 7;</code>
   */

  com.facebook.buck.cd.model.kotlin.PluginParams getKotlinCompilerPluginsOrThrow(
      java.lang.String key);

  /**
   * <code>map&lt;string, .AbsPath&gt; kosabiPluginOptions = 8;</code>
   */
  int getKosabiPluginOptionsCount();
  /**
   * <code>map&lt;string, .AbsPath&gt; kosabiPluginOptions = 8;</code>
   */
  boolean containsKosabiPluginOptions(
      java.lang.String key);
  /**
   * Use {@link #getKosabiPluginOptionsMap()} instead.
   */
  @java.lang.Deprecated
  java.util.Map<java.lang.String, com.facebook.buck.cd.model.common.AbsPath>
  getKosabiPluginOptions();
  /**
   * <code>map&lt;string, .AbsPath&gt; kosabiPluginOptions = 8;</code>
   */
  java.util.Map<java.lang.String, com.facebook.buck.cd.model.common.AbsPath>
  getKosabiPluginOptionsMap();
  /**
   * <code>map&lt;string, .AbsPath&gt; kosabiPluginOptions = 8;</code>
   */

  com.facebook.buck.cd.model.common.AbsPath getKosabiPluginOptionsOrDefault(
      java.lang.String key,
      com.facebook.buck.cd.model.common.AbsPath defaultValue);
  /**
   * <code>map&lt;string, .AbsPath&gt; kosabiPluginOptions = 8;</code>
   */

  com.facebook.buck.cd.model.common.AbsPath getKosabiPluginOptionsOrThrow(
      java.lang.String key);

  /**
   * <code>repeated .AbsPath friendPaths = 9;</code>
   */
  java.util.List<com.facebook.buck.cd.model.common.AbsPath> 
      getFriendPathsList();
  /**
   * <code>repeated .AbsPath friendPaths = 9;</code>
   */
  com.facebook.buck.cd.model.common.AbsPath getFriendPaths(int index);
  /**
   * <code>repeated .AbsPath friendPaths = 9;</code>
   */
  int getFriendPathsCount();
  /**
   * <code>repeated .AbsPath friendPaths = 9;</code>
   */
  java.util.List<? extends com.facebook.buck.cd.model.common.AbsPathOrBuilder> 
      getFriendPathsOrBuilderList();
  /**
   * <code>repeated .AbsPath friendPaths = 9;</code>
   */
  com.facebook.buck.cd.model.common.AbsPathOrBuilder getFriendPathsOrBuilder(
      int index);

  /**
   * <code>repeated .AbsPath kotlinHomeLibraries = 10;</code>
   */
  java.util.List<com.facebook.buck.cd.model.common.AbsPath> 
      getKotlinHomeLibrariesList();
  /**
   * <code>repeated .AbsPath kotlinHomeLibraries = 10;</code>
   */
  com.facebook.buck.cd.model.common.AbsPath getKotlinHomeLibraries(int index);
  /**
   * <code>repeated .AbsPath kotlinHomeLibraries = 10;</code>
   */
  int getKotlinHomeLibrariesCount();
  /**
   * <code>repeated .AbsPath kotlinHomeLibraries = 10;</code>
   */
  java.util.List<? extends com.facebook.buck.cd.model.common.AbsPathOrBuilder> 
      getKotlinHomeLibrariesOrBuilderList();
  /**
   * <code>repeated .AbsPath kotlinHomeLibraries = 10;</code>
   */
  com.facebook.buck.cd.model.common.AbsPathOrBuilder getKotlinHomeLibrariesOrBuilder(
      int index);

  /**
   * <code>string jvmTarget = 11;</code>
   */
  java.lang.String getJvmTarget();
  /**
   * <code>string jvmTarget = 11;</code>
   */
  com.google.protobuf.ByteString
      getJvmTargetBytes();

  /**
   * <code>bool shouldGenerateAnnotationProcessingStats = 12;</code>
   */
  boolean getShouldGenerateAnnotationProcessingStats();

  /**
   * <code>bool shouldVerifySourceOnlyAbiConstraints = 13;</code>
   */
  boolean getShouldVerifySourceOnlyAbiConstraints();
}
