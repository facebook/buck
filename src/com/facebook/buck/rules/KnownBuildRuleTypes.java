/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules;

import com.facebook.buck.android.AndroidBinaryBuildRuleFactory;
import com.facebook.buck.android.AndroidInstrumentationApkRuleFactory;
import com.facebook.buck.android.AndroidLibraryBuildRuleFactory;
import com.facebook.buck.android.AndroidManifestDescription;
import com.facebook.buck.android.AndroidResourceBuildRuleFactory;
import com.facebook.buck.android.ApkGenruleBuildRuleFactory;
import com.facebook.buck.android.GenAidlDescription;
import com.facebook.buck.android.NdkLibraryBuildRuleFactory;
import com.facebook.buck.android.PrebuiltNativeLibraryBuildRuleFactory;
import com.facebook.buck.android.RobolectricTestBuildRuleFactory;
import com.facebook.buck.apple.IosBinaryDescription;
import com.facebook.buck.apple.IosLibraryDescription;
import com.facebook.buck.apple.IosTestDescription;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.java.JavaBinaryBuildRuleFactory;
import com.facebook.buck.java.JavaLibraryBuildRuleFactory;
import com.facebook.buck.java.JavaTestBuildRuleFactory;
import com.facebook.buck.java.KeystoreBuildRuleFactory;
import com.facebook.buck.java.PrebuiltJarBuildRuleFactory;
import com.facebook.buck.parcelable.GenParcelableBuildRuleFactory;
import com.facebook.buck.parser.ProjectConfigRuleFactory;
import com.facebook.buck.python.PythonBinaryBuildRuleFactory;
import com.facebook.buck.python.PythonLibraryDescription;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.GenruleBuildRuleFactory;
import com.facebook.buck.shell.ShBinaryBuildRuleFactory;
import com.facebook.buck.shell.ShTestBuildRuleFactory;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * A registry of all the build rules types understood by Buck.
 */
public class KnownBuildRuleTypes {

  private final ImmutableSet<Description<?>> descriptions;
  private final ImmutableMap<BuildRuleType, BuildRuleFactory<?>> factories;
  private final ImmutableMap<String, BuildRuleType> types;
  private static final KnownBuildRuleTypes DEFAULT = createDefaultBuilder().build();


  private KnownBuildRuleTypes(Set<Description<?>> descriptions,
      Map<BuildRuleType, BuildRuleFactory<?>> factories,
      Map<String, BuildRuleType> types) {
    this.descriptions = ImmutableSet.copyOf(descriptions);
    this.factories = ImmutableMap.copyOf(factories);
    this.types = ImmutableMap.copyOf(types);
  }

  public BuildRuleType getBuildRuleType(String named) {
    BuildRuleType type = types.get(named);
    if (type == null) {
      throw new HumanReadableException("Unable to find build rule type: " + named);
    }
    return type;
  }

  public BuildRuleFactory<?> getFactory(BuildRuleType buildRuleType) {
    BuildRuleFactory<?> factory = factories.get(buildRuleType);
    if (factory == null) {
      throw new HumanReadableException(
          "Unable to find factory for build rule type: " + buildRuleType);
    }
    return factory;
  }

  public ImmutableSet<Description<?>> getAllDescriptions() {
    return ImmutableSet.copyOf(descriptions);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static KnownBuildRuleTypes getDefault() {
    return DEFAULT;
  }

  public static Builder createDefaultBuilder() {
    Builder builder = builder();

    builder.register(new AndroidManifestDescription());
    builder.register(new ExportFileDescription());
    builder.register(new GenAidlDescription());
    builder.register(new IosBinaryDescription());
    builder.register(new IosLibraryDescription());
    builder.register(new IosTestDescription());
    builder.register(new PythonLibraryDescription());
    builder.register(new XcodeNativeDescription());

    // TODO(simons): Consider once more whether we actually want to have default rules
    builder.register(BuildRuleType.ANDROID_BINARY, new AndroidBinaryBuildRuleFactory());
    builder.register(BuildRuleType.ANDROID_INSTRUMENTATION_APK,
        new AndroidInstrumentationApkRuleFactory());
    builder.register(BuildRuleType.ANDROID_LIBRARY, new AndroidLibraryBuildRuleFactory());
    builder.register(BuildRuleType.ANDROID_RESOURCE, new AndroidResourceBuildRuleFactory());
    builder.register(BuildRuleType.APK_GENRULE, new ApkGenruleBuildRuleFactory());
    builder.register(BuildRuleType.GENRULE, new GenruleBuildRuleFactory());
    builder.register(BuildRuleType.JAVA_LIBRARY,
        new JavaLibraryBuildRuleFactory(Optional.<Path>absent()));
    builder.register(BuildRuleType.JAVA_TEST, new JavaTestBuildRuleFactory());
    builder.register(BuildRuleType.JAVA_BINARY, new JavaBinaryBuildRuleFactory());
    builder.register(BuildRuleType.KEYSTORE, new KeystoreBuildRuleFactory());
    builder.register(BuildRuleType.GEN_PARCELABLE, new GenParcelableBuildRuleFactory());
    builder.register(BuildRuleType.NDK_LIBRARY, new NdkLibraryBuildRuleFactory());
    builder.register(BuildRuleType.PREBUILT_JAR, new PrebuiltJarBuildRuleFactory());
    builder.register(BuildRuleType.PREBUILT_NATIVE_LIBRARY,
        new PrebuiltNativeLibraryBuildRuleFactory());
    builder.register(BuildRuleType.PROJECT_CONFIG, new ProjectConfigRuleFactory());
    builder.register(BuildRuleType.PYTHON_BINARY, new PythonBinaryBuildRuleFactory());
    builder.register(BuildRuleType.ROBOLECTRIC_TEST, new RobolectricTestBuildRuleFactory());
    builder.register(BuildRuleType.SH_BINARY, new ShBinaryBuildRuleFactory());
    builder.register(BuildRuleType.SH_TEST, new ShTestBuildRuleFactory());

    return builder;
  }

  public static KnownBuildRuleTypes getConfigured(BuckConfig buckConfig) {
    return createConfiguredBuilder(buckConfig).build();
  }

  public static Builder createConfiguredBuilder(BuckConfig buckConfig) {
    Builder builder = createDefaultBuilder();
    builder.register(BuildRuleType.JAVA_LIBRARY,
        new JavaLibraryBuildRuleFactory(buckConfig.getJavac()));
    return builder;
  }

  public static class Builder {
    private final Set<Description<?>> descriptions;
    private final Map<BuildRuleType, BuildRuleFactory<?>> factories;
    private final Map<String, BuildRuleType> types;

    protected Builder() {
      this.descriptions = Sets.newConcurrentHashSet();
      this.factories = Maps.newConcurrentMap();
      this.types = Maps.newConcurrentMap();
    }

    public void register(BuildRuleType type, BuildRuleFactory<?> factory) {
      Preconditions.checkNotNull(type);
      Preconditions.checkNotNull(factory);
      types.put(type.getName(), type);
      factories.put(type, factory);
    }

    public void register(Description<?> description) {
      Preconditions.checkNotNull(description);
      BuildRuleType type = description.getBuildRuleType();
      types.put(type.getName(), type);
      factories.put(type, new DescribedRuleFactory<>(description));
      descriptions.add(description);
    }

    public KnownBuildRuleTypes build() {
      return new KnownBuildRuleTypes(descriptions, factories, types);
    }
  }
}
