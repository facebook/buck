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
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import org.kohsuke.args4j.Option;

import java.util.Set;

import javax.annotation.Nullable;

public class TestCommandOptions extends BuildCommandOptions {

  public static final String LABEL_SEPERATOR = "+";
  public static final String USE_RESULTS_CACHE = "use_results_cache";

  @Option(name = "--all", usage = "Whether all of the tests should be run.")
  private boolean all = false;

  @Option(name = "--code-coverage", usage = "Whether code coverage information will be generated.")
  private boolean isCodeCoverageEnabled = false;

  @Option(name = "--debug", usage = "Whether the test will start suspended with a JDWP debug port of 5005")
  private boolean isDebugEnabled = false;

  @Option(name = "--xml", usage = "Where to write test output as XML.")
  @Nullable
  private String pathToXmlTestOutput = null;

  @Option(name = "--jacoco", usage = "Whether jacoco should be used for code coverage analysis or emma.")
  private boolean isJaccoEnabled = false;

  @Option(name = "--no-results-cache", usage = "Whether to use cached test results.")
  private boolean isResultsCacheDisabled = false;

  @Option(
      name = "--ignore-when-dependencies-fail",
      aliases = {"-i"},
      usage =
          "Ignore test failures for libraries if they depend on other libraries " +
          "that aren't passing their tests.  " +
          "For example, if java_library A depends on B, " +
          "and they are tested respectively by T1 and T2 and both of those tests fail, " +
          "only print the error for T2.")
  private boolean isIgnoreFailingDependencies = false;

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

  @Option(
      name = "--dry-run",
      usage = "Print tests that match the given command line options, but don't run them.")
  private boolean printMatchingTestRules;

  @AdditionalOptions
  private TargetDeviceOptions targetDeviceOptions;

  @AdditionalOptions
  private TestSelectorOptions testSelectorOptions;

  private Supplier<ImmutableSet<ImmutableSet<String>>> includedLabelsSupplier =
      Suppliers.memoize(new Supplier<ImmutableSet<ImmutableSet<String>>>() {
        @Override
        public ImmutableSet<ImmutableSet<String>> get() {
          return splitLabels(includedSet.get());
        }
      });

  private Supplier<ImmutableSet<ImmutableSet<String>>> excludedLabelsSupplier =
      Suppliers.memoize(new Supplier<ImmutableSet<ImmutableSet<String>>>() {
        @Override
        public ImmutableSet<ImmutableSet<String>> get() {
          return splitLabels(excludedSet.get());
        }
      });

  private Supplier<ImmutableSet<ImmutableSet<String>>> globalExcludedLabelsSupplier =
      Suppliers.memoize(new Supplier<ImmutableSet<ImmutableSet<String>>>() {
        @Override
        public ImmutableSet<ImmutableSet<String>> get() {
          return splitLabels(getBuckConfig().getDefaultExcludedLabels());
        }
      });

  public TestCommandOptions(BuckConfig buckConfig) {
    super(buckConfig);

    setUseResultsCacheFromConfig(buckConfig);
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

  private void setUseResultsCacheFromConfig(BuckConfig buckConfig) {
    // The command line option is a negative one, hence the slightly confusing logic.
    boolean isUseResultsCache = buckConfig.getBooleanValue("test", USE_RESULTS_CACHE, true);
    isResultsCacheDisabled = !isUseResultsCache;
  }

  public boolean isResultsCacheEnabled() {
    // The option is negative (--no-X) but we prefer to reason about positives, in the code.
    return !isResultsCacheDisabled;
  }

  @Override
  public boolean isDebugEnabled() {
    return isDebugEnabled;
  }

  public boolean isIgnoreFailingDependencies() {
    return isIgnoreFailingDependencies;
  }

  public Optional<TargetDevice> getTargetDeviceOptional() {
    return targetDeviceOptions.getTargetDeviceOptional();
  }

  public Optional<TestSelectorList> getTestSelectorListOptional() {
    return testSelectorOptions.getTestSelectorListOptional();
  }

  public boolean shouldExplainTestSelectorList() {
    return testSelectorOptions.shouldExplain();
  }

  /**
   * See if we include (either by default, or explicit inclusion) or explicitly exclude a set of
   * labels.
   *
   * @param labels A candidate set of labels -- {a, b, j, k} -- that we are checking.
   */
  public boolean isMatchedByLabelOptions(Set<String> labels) {
    // A set of subsets of labels -- { {a, b}, {x, y} } -- that we include.
    ImmutableSet<ImmutableSet<String>> included = includedLabelsSupplier.get();
    ImmutableSet<ImmutableSet<String>> excluded = excludedLabelsSupplier.get();

    // Don't include the global labels in this check, as it's likely we might do the following:
    //
    //   config file: exclude X     ...to, by default, never run X tests
    //   --include: X               ...for the rare occasions we do actually want to run X tests
    //
    Sets.SetView<ImmutableSet<String>> intersection = Sets.intersection(included, excluded);
    if (!intersection.isEmpty()) {
      ImmutableSet.Builder<String> builder = new ImmutableSet.Builder<>();
      for (ImmutableSet<String> labelSet : intersection) {
        String setString = Joiner.on(LABEL_SEPERATOR).join(labelSet);
        builder.add(setString);
      }
      String message = "You have specified labels that are both included and excluded: " +
          Joiner.on(", ").join(builder.build());
      throw new HumanReadableException(message);
    }

    // If non-empty, only include if we have a --include that matches.
    if (!included.isEmpty()) {
      for (ImmutableSet<String> comparisonLabels : included) {
        // An individual set of labels -- {a, b} -- all of which have be in our candidate.
        if (labels.containsAll(comparisonLabels)) {
          return true;
        }
      }
      return false;
    }

    // If any exclude exists that matches, we should exclude.
    ImmutableSet<ImmutableSet<String>> globalExcluded = globalExcludedLabelsSupplier.get();
    Sets.SetView<ImmutableSet<String>> allExcluded = Sets.union(excluded, globalExcluded);
    for (ImmutableSet<String> comparisonLabels : allExcluded) {
      if (labels.containsAll(comparisonLabels)) {
        return false;
      }
    }

    return true;
  }

  /**
   * Split a set of "a+b" "x+y" args into {{"a", "b"}, {"x", "y"}}.
   */
  private ImmutableSet<ImmutableSet<String>> splitLabels(Set<String> labelSets) {
    // This could be an ImmutableOrderedSet but it's hard to find an order for a Set<Set<String>>.
    ImmutableSet.Builder<ImmutableSet<String>> disjunction = new ImmutableSet.Builder<>();
    for (String labelSet : labelSets) {
      ImmutableSet.Builder<String> conjunction = new ImmutableSet.Builder<>();
      Iterable<String> split = Splitter.on(LABEL_SEPERATOR)
          .trimResults().omitEmptyStrings().split(labelSet);
      for (String labelName : split) {
        BuckConfig.validateLabelName(labelName);
        conjunction.add(labelName);
      }
      disjunction.add(conjunction.build());
    }
    return disjunction.build();
  }

  public boolean isPrintMatchingTestRules() {
    return printMatchingTestRules;
  }
}
