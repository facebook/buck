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

package com.facebook.buck.cli;

import com.facebook.buck.java.DefaultJavaPackageFinder;
import com.facebook.buck.step.TargetDevice;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import org.kohsuke.args4j.Option;

import java.util.Set;

import javax.annotation.Nullable;

public class TestCommandOptions extends BuildCommandOptions {

  @Option(name = "--all", usage = "Whether all of the tests should be run.")
  private boolean all = false;

  @Option(name = "--code-coverage", usage = "Whether code coverage information will be generated.")
  private boolean isCodeCoverageEnabled = false;

  @Option(name = "--debug", usage = "Whether the test will start suspended with a JDWP debug port of 5005")
  private boolean isDebugEnabled = false;

  @Option(name = "--xml", usage = "Where to write test output as XML.")
  @Nullable
  private String pathToXmlTestOutput = null;

  @Option(name ="--jacoco", usage = "Whether jacoco should be used for code coverage analysis or emma.")
  private boolean isJaccoEnabled = false;

  @Option(
      name = "--include",
      usage = "Labels to include when running tests, --include L1 L2 ... LN --other_option.",
      handler = StringSetOptionHandler.class)
  private Supplier<ImmutableSet<String>> includedSet;

  @Option(
      name = "--exclude",
      usage = "Labels to ignore when running tests, --exclude L1 L2 ... LN --other_option.",
      handler = StringSetOptionHandler.class)
  private Supplier<ImmutableSet<String>> excludedSet;

  @AdditionalOptions
  private TargetDeviceOptions targetDeviceOptions;

  private static ImmutableSet.Builder<String> validateLabels(Set<String> labelSet) {
    ImmutableSet.Builder<String> result = ImmutableSet.builder();
    for (String label : labelSet) {
      BuckConfig.validateLabelName(label);
    }
    result.addAll(labelSet);
    return result;
  }

  private Supplier<ImmutableSet<String>> includedLabelsSupplier =
      Suppliers.memoize(new Supplier<ImmutableSet<String>>() {
        @Override
        public ImmutableSet<String> get() {
          ImmutableSet.Builder<String> result = validateLabels(includedSet.get());
          return result.build();
        }
  });

  private Supplier<ImmutableSet<String>> excludedLabelsSupplier =
      Suppliers.memoize(new Supplier<ImmutableSet<String>>() {
        @Override
        public ImmutableSet<String> get() {
          ImmutableSet.Builder<String> result = validateLabels(excludedSet.get());
          result.addAll(getBuckConfig().getDefaultExcludedLabels());
          ImmutableSet<String> allExcluded = result.build();

          // If someone has included a test, then we should really run it.
          return Sets.difference(allExcluded, getIncludedLabels()).immutableCopy();
        }
  });

  public TestCommandOptions(BuckConfig buckConfig) {
    super(buckConfig);
  }

  public boolean isRunAllTests() {
    return all;
  }

  @Nullable
  public String getPathToXmlTestOutput() {
    return pathToXmlTestOutput;
  }

  public Optional<DefaultJavaPackageFinder> getJavaPackageFinder() {
    return Optional.fromNullable(getBuckConfig().createDefaultJavaPackageFinder());
  }

  @Override
  public boolean isCodeCoverageEnabled() {
    return isCodeCoverageEnabled;
  }

  @Override
  public boolean isJacocoEnabled() {
    return isJaccoEnabled;
  }

  @Override
  public boolean isDebugEnabled() {
    return isDebugEnabled;
  }

  public ImmutableSet<String> getIncludedLabels() {
    return includedLabelsSupplier.get();
  }

  public ImmutableSet<String> getExcludedLabels() {
    return excludedLabelsSupplier.get();
  }

  public Optional<TargetDevice> getTargetDeviceOptional() {
    return targetDeviceOptions.getTargetDeviceOptional();
  }
}
