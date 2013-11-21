/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.java;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.rules.AbiRule;
import com.facebook.buck.rules.AbstractBuildRuleBuilderParams;
import com.facebook.buck.rules.AbstractBuildable;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Buildable;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.BuckConstant;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * {@link Buildable} that writes the list of {@code .class} files found in a zip or directory to a
 * file.
 */
public class AccumulateClassNames extends AbstractBuildable
    implements InitializableFromDisk<AccumulateClassNames.ClassNamesAndAbiKey> {

  private static final Splitter CLASS_NAME_AND_HASH_SPLITTER = Splitter.on(
      AccumulateClassNamesStep.CLASS_NAME_HASH_CODE_SEPARATOR);

  private final JavaLibraryRule javaLibraryRule;
  private final Path pathToOutputFile;

  @Nullable
  private ClassNamesAndAbiKey buildOutput;

  @VisibleForTesting
  AccumulateClassNames(BuildTarget buildTarget, JavaLibraryRule javaLibraryRule) {
    Preconditions.checkNotNull(buildTarget);
    this.javaLibraryRule = Preconditions.checkNotNull(javaLibraryRule);
    this.pathToOutputFile = Paths.get(
        BuckConstant.GEN_DIR,
        buildTarget.getBasePath(),
        buildTarget.getShortName() + ".classes.txt");
  }

  @Override
  public Iterable<String> getInputsToCompareToOutput() {
    // The deps of this rule already capture all of the inputs that should affect the cache key.
    return ImmutableSortedSet.of();
  }

  @Override
  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder;
  }

  @Override
  public String getPathToOutputFile() {
    return pathToOutputFile.toString();
  }

  public JavaLibraryRule getJavaLibraryRule() {
    return javaLibraryRule;
  }

  @Override
  public List<Step> getBuildSteps(BuildContext context, final BuildableContext buildableContext)
      throws IOException {
    ImmutableList.Builder<Step> steps = ImmutableList.builder();

    steps.add(new RmStep(getPathToOutputFile(), /* shouldForceDeletion */ true));

    // Make sure that the output directory exists for the output file.
    steps.add(new MkdirStep(pathToOutputFile.getParent()));

    final AccumulateClassNamesStep accumulateClassNamesStep = new AccumulateClassNamesStep(
        Paths.get(javaLibraryRule.getPathToOutputFile()),
        Paths.get(getPathToOutputFile()));
    steps.add(accumulateClassNamesStep);

    AbstractExecutionStep recordAbiStep = new AbstractExecutionStep("record_abi") {
      @Override
      public int execute(ExecutionContext context) {
        Sha1HashCode abiKey = computeAbiKey(accumulateClassNamesStep);
        buildableContext.addMetadata(AbiRule.ABI_KEY_ON_DISK_METADATA, abiKey.toString());
        return 0;
      }
    };
    steps.add(recordAbiStep);

    return steps.build();
  }

  @Override
  public ClassNamesAndAbiKey initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
    // Read the output file, which should now be in place because this rule was downloaded from
    // cache.
    List<String> lines;
    try {
      lines = onDiskBuildInfo.getOutputFileContentsByLine(this);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // Use the contents of the file to create the ImmutableSortedMap<String, HashCode>.
    ImmutableSortedMap.Builder<String, HashCode> classNamesBuilder = ImmutableSortedMap.naturalOrder();
    for (String line : lines) {
      List<String> parts = CLASS_NAME_AND_HASH_SPLITTER.splitToList(line);
      Preconditions.checkState(parts.size() == 2);
      String key = parts.get(0);
      HashCode value = HashCode.fromString(parts.get(1));
      classNamesBuilder.put(key, value);
    }

    return new ClassNamesAndAbiKey(onDiskBuildInfo.getHash(AbiRule.ABI_KEY_ON_DISK_METADATA).get(),
        classNamesBuilder.build());
  }

  public static class ClassNamesAndAbiKey {

    private final Sha1HashCode abiKey;

    /**
     * This will contain the classes info discovered in the course of building this {@link Buildable}.
     */
    private final ImmutableSortedMap<String, HashCode> classNames;

    public ClassNamesAndAbiKey(Sha1HashCode abiKey,
        ImmutableSortedMap<String, HashCode> classNames) {
      this.abiKey = Preconditions.checkNotNull(abiKey);
      this.classNames = Preconditions.checkNotNull(classNames);
    }

    public Sha1HashCode getAbiKey() {
      return abiKey;
    }

    public ImmutableSortedMap<String, HashCode> getClassNames() {
      return classNames;
    }
  }

  @Override
  public void setBuildOutput(ClassNamesAndAbiKey buildOutput) {
    Preconditions.checkState(this.buildOutput == null,
        "buildOutput should not already be set for %s.",
        this);
    this.buildOutput = buildOutput;
  }

  @Override
  public ClassNamesAndAbiKey getBuildOutput() {
    Preconditions.checkState(buildOutput != null, "buildOutput must already be set for %s.", this);
    return buildOutput;
  }

  public ImmutableSortedMap<String, HashCode> getClassNames() {
    return getBuildOutput().classNames;
  }

  public Sha1HashCode getAbiKey() {
    return getBuildOutput().abiKey;
  }

  @VisibleForTesting
  static Sha1HashCode computeAbiKey(Supplier<ImmutableSortedMap<String, HashCode>> classNames) {
    Hasher hasher = Hashing.sha1().newHasher();
    for (Map.Entry<String, HashCode> entry : classNames.get().entrySet()) {
      hasher.putUnencodedChars(entry.getKey());
      hasher.putByte((byte)0);
      hasher.putUnencodedChars(entry.getValue().toString());
      hasher.putByte((byte)0);
    }
    return new Sha1HashCode(hasher.hash().toString());
  }

  public static Builder newAccumulateClassNamesBuilder(AbstractBuildRuleBuilderParams params) {
    return new Builder(params);
  }

  public static class Builder extends AbstractBuildable.Builder {

    private JavaLibraryRule javaLibraryRule;

    private Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    @Override
    protected BuildRuleType getType() {
      return BuildRuleType._CLASS_NAMES;
    }

    @Override
    protected AccumulateClassNames newBuildable(BuildRuleParams params,
        BuildRuleResolver resolver) {
      return new AccumulateClassNames(params.getBuildTarget(), javaLibraryRule);
    }

    public Builder setJavaLibraryToDex(JavaLibraryRule javaLibraryRule) {
      this.javaLibraryRule = javaLibraryRule;
      this.addDep(javaLibraryRule.getBuildTarget());
      return this;
    }

    @Override
    public Builder setBuildTarget(BuildTarget buildTarget) {
      super.setBuildTarget(buildTarget);
      return this;
    }

    @Override
    public Builder addDep(BuildTarget buildTarget) {
      super.addDep(buildTarget);
      return this;
    }

    @Override
    public Builder addVisibilityPattern(BuildTargetPattern visibilityPattern) {
      super.addVisibilityPattern(visibilityPattern);
      return this;
    }
  }
}
