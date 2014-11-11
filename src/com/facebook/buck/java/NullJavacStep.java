/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.java.abi.AbiWriterProtocol;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildDependencies;
import com.facebook.buck.rules.Sha1HashCode;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * A {@link JavacStep} that doesn't do any building, but adheres to the contract of the class. One
 * handy feature of this class is that it returns the empty abi key.
 */
public class NullJavacStep extends JavacStep {

  private static final Sha1HashCode EMPTY_KEY = new Sha1HashCode(AbiWriterProtocol.EMPTY_ABI_KEY);

  public NullJavacStep(
      Path outputDirectory,
      JavacOptions javacOptions,
      Optional<BuildTarget> invokingRule,
      BuildDependencies buildDependencies,
      Optional<SuggestBuildRules> suggestBuildRules) {
    super(
        outputDirectory,
        ImmutableSet.<Path>of(),
        ImmutableSet.<Path>of(),
        ImmutableSet.<Path>of(),
        javacOptions,
        Optional.<Path>absent(),
        invokingRule,
        buildDependencies,
        suggestBuildRules,
        Optional.<Path>absent());
  }

  @Nullable
  @Override
  public Sha1HashCode getAbiKey() {
    return EMPTY_KEY;
  }

  @Override
  protected int buildWithClasspath(
      ExecutionContext context, Set<Path> buildClasspathEntries) throws InterruptedException {
    // Nothing to do.
    return 0;
  }

  @Override
  public String getShortName() {
    return "not compiling";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return getShortName();
  }
}
