/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import com.facebook.buck.hashing.FileHashLoader;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.IOException;
import java.nio.file.Path;

import javax.annotation.Nonnull;

public class InputCountingRuleKeyBuilderFactory extends
    ReflectiveRuleKeyBuilderFactory<
        RuleKeyBuilder<InputCountingRuleKeyBuilderFactory.Result>,
        InputCountingRuleKeyBuilderFactory.Result> {

  protected final LoadingCache<RuleKeyAppendable, InputCountingRuleKeyBuilderFactory.Result>
      resultCache;
  private final FileHashLoader hashLoader;
  private final SourcePathResolver pathResolver;

  public InputCountingRuleKeyBuilderFactory(
      int seed,
      FileHashLoader hashLoader,
      SourcePathResolver pathResolver) {
    super(seed);
    this.resultCache = CacheBuilder.newBuilder().weakKeys().build(
        new CacheLoader<RuleKeyAppendable, InputCountingRuleKeyBuilderFactory.Result>() {
          @Override
          public InputCountingRuleKeyBuilderFactory.Result load(
              @Nonnull RuleKeyAppendable appendable) throws Exception {
            RuleKeyBuilder<InputCountingRuleKeyBuilderFactory.Result> subKeyBuilder = newBuilder();
            appendable.appendToRuleKey(subKeyBuilder);
            return subKeyBuilder.build();
          }
        });
    this.hashLoader = hashLoader;
    this.pathResolver = pathResolver;
  }

  private RuleKeyBuilder<InputCountingRuleKeyBuilderFactory.Result> newBuilder() {
    return new RuleKeyBuilder<InputCountingRuleKeyBuilderFactory.Result>(pathResolver, hashLoader) {

      private int inputsCount;
      private long inputsSize;
      private ImmutableSet.Builder<Path> paths = ImmutableSet.builder();
      private ImmutableSet.Builder<SourcePath> sourcePaths = ImmutableSet.builder();

      @Override
      protected RuleKeyBuilder<InputCountingRuleKeyBuilderFactory.Result> setBuildRule(
          BuildRule rule) {
        // Ignore build rules, if the rule is used as an input it will be a SourcePath
        return this;
      }

      @Override
      public RuleKeyBuilder<InputCountingRuleKeyBuilderFactory.Result> setAppendableRuleKey(
          String key,
          RuleKeyAppendable appendable) {
        InputCountingRuleKeyBuilderFactory.Result result = resultCache.getUnchecked(appendable);
        inputsCount += result.getInputsCount();
        inputsSize += result.getInputsSize();
        return this;
      }

      @Override
      protected RuleKeyBuilder<Result> setNonHashingSourcePath(SourcePath sourcePath) {
        return setSourcePath(sourcePath);
      }

      @Override
      protected RuleKeyBuilder<Result> setSourcePath(SourcePath sourcePath) {
        sourcePaths.add(sourcePath);
        return this;
      }

      @Override
      public RuleKeyBuilder<Result> setPath(Path absolutePath, Path ideallyRelative)
          throws IOException {
        paths.add(absolutePath);
        return this;
      }

      @Override
      public RuleKeyBuilder<Result> setArchiveMemberPath(
          ArchiveMemberPath absoluteArchiveMemberPath,
          ArchiveMemberPath relativeArchiveMemberPath) throws IOException {
        paths.add(absoluteArchiveMemberPath.getArchivePath());
        return this;
      }

      @Override
      public InputCountingRuleKeyBuilderFactory.Result build() {
        try {
          for (Path path :
              Iterables.concat(
                  paths.build(),
                  pathResolver.getAllAbsolutePaths(sourcePaths.build()))) {
            inputsCount += 1;
            inputsSize += hashLoader.getSize(path);
          }
        } catch (IOException e) {
          throw new WrappedIoException(e);
        }
        return new Result(inputsCount, inputsSize);
      }
    };
  }

  @Override
  protected RuleKeyBuilder<InputCountingRuleKeyBuilderFactory.Result> newBuilder(BuildRule rule) {
    return newBuilder();
  }

  public static class Result {

    private final int inputsCount;
    private final long inputsSize;

    public Result(
        int inputsCount,
        long inputsSize) {
      this.inputsCount = inputsCount;
      this.inputsSize = inputsSize;
    }

    public int getInputsCount() {
      return inputsCount;
    }

    public long getInputsSize() {
      return inputsSize;
    }

  }

  public static class WrappedIoException extends RuntimeException {

    public WrappedIoException(Exception e) {
      super(e);
    }

  }

}
