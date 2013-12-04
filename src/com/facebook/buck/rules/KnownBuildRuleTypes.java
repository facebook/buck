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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.util.Map;
import java.util.Set;

/**
 * A registry of all the build rules types understood by Buck.
 */
public class KnownBuildRuleTypes {

  private final Set<Description<?>> descriptions = Sets.newConcurrentHashSet();
  private Map<BuildRuleType, BuildRuleFactory<?>> factories = Maps.newConcurrentMap();
  private Map<String, BuildRuleType> types = Maps.newConcurrentMap();

  public KnownBuildRuleTypes() {
    register(new AndroidManifestDescription());
    register(new ExportFileDescription());
    register(new GenAidlDescription());
    register(new PythonLibraryDescription());

    // TODO(simons): Consider whether we actually want to have default rules
    register(BuildRuleType.ANDROID_BINARY, new AndroidBinaryBuildRuleFactory());
    register(BuildRuleType.ANDROID_INSTRUMENTATION_APK, new AndroidInstrumentationApkRuleFactory());
    register(BuildRuleType.ANDROID_LIBRARY, new AndroidLibraryBuildRuleFactory());
    register(BuildRuleType.ANDROID_RESOURCE, new AndroidResourceBuildRuleFactory());
    register(BuildRuleType.APK_GENRULE, new ApkGenruleBuildRuleFactory());
    register(BuildRuleType.GENRULE, new GenruleBuildRuleFactory());
    register(BuildRuleType.JAVA_LIBRARY, new JavaLibraryBuildRuleFactory());
    register(BuildRuleType.JAVA_TEST, new JavaTestBuildRuleFactory());
    register(BuildRuleType.JAVA_BINARY, new JavaBinaryBuildRuleFactory());
    register(BuildRuleType.KEYSTORE, new KeystoreBuildRuleFactory());
    register(BuildRuleType.GEN_PARCELABLE, new GenParcelableBuildRuleFactory());
    register(BuildRuleType.NDK_LIBRARY, new NdkLibraryBuildRuleFactory());
    register(BuildRuleType.PREBUILT_JAR, new PrebuiltJarBuildRuleFactory());
    register(BuildRuleType.PREBUILT_NATIVE_LIBRARY, new PrebuiltNativeLibraryBuildRuleFactory());
    register(BuildRuleType.PROJECT_CONFIG, new ProjectConfigRuleFactory());
    register(BuildRuleType.PYTHON_BINARY, new PythonBinaryBuildRuleFactory());
    register(BuildRuleType.ROBOLECTRIC_TEST, new RobolectricTestBuildRuleFactory());
    register(BuildRuleType.SH_BINARY, new ShBinaryBuildRuleFactory());
    register(BuildRuleType.SH_TEST, new ShTestBuildRuleFactory());
  }

  public void register(BuildRuleType type, BuildRuleFactory<?> factory) {
    Preconditions.checkNotNull(type);
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
}
