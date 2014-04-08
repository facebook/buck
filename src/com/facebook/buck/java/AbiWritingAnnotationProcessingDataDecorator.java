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

import com.facebook.buck.java.abi.AbiWriterProtocol;
import com.facebook.buck.rules.AnnotationProcessingData;
import com.facebook.buck.rules.RuleKey;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSortedSet;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;

/**
 * An {@link AnnotationProcessingDataDecorator} that adds
 * {@link com.facebook.buck.java.abi.AbiWriter} to the processor path of an
 * {@link AnnotationProcessingData}.
 */
public class AbiWritingAnnotationProcessingDataDecorator
    implements AnnotationProcessingDataDecorator {

  @VisibleForTesting
  static final Path ABI_PROCESSOR_CLASSPATH = Paths.get(System.getProperty(
      "buck.abi_processor_classes", new File("build/abi_processor/classes").getAbsolutePath()));

  private final File outputFile;

  public AbiWritingAnnotationProcessingDataDecorator(File outputFile) {
    this.outputFile = Preconditions.checkNotNull(outputFile);
  }

  @Override
  public AnnotationProcessingData decorate(final AnnotationProcessingData delegate) {
    return new AnnotationProcessingData() {

      @Override
      public boolean isEmpty() {
        return false;
      }

      @Override
      public ImmutableSortedSet<Path> getSearchPathElements() {
        return ImmutableSortedSet.<Path>naturalOrder()
            .addAll(delegate.getSearchPathElements())
            .add(ABI_PROCESSOR_CLASSPATH)
            .build();
      }

      @Override
      public ImmutableSortedSet<String> getNames() {
        return ImmutableSortedSet.<String>naturalOrder()
            .addAll(delegate.getNames())
            .add(AbiWriterProtocol.ABI_ANNOTATION_PROCESSOR_CLASS_NAME)
            .build();
      }

      @Override
      public ImmutableSortedSet<String> getParameters() {
        return ImmutableSortedSet.<String>naturalOrder()
            .addAll(delegate.getParameters())
            .add(AbiWriterProtocol.PARAM_ABI_OUTPUT_FILE + "=" + outputFile.getAbsolutePath())
            .build();
      }

      @Override
      public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
        // We don't need to add any local state here since all RuleKeys change whenever
        // buck changes.
        return delegate.appendToRuleKey(builder);
      }

      @Override
      public boolean getProcessOnly() {
        return delegate.getProcessOnly();
      }

      @Override
      @Nullable
      public Path getGeneratedSourceFolderName() {
        return delegate.getGeneratedSourceFolderName();
      }

    };
  }

}
