/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.io.FileScrubber;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class LazyDelegatingArchiver implements Archiver {
  private Supplier<Archiver> delegate;

  public LazyDelegatingArchiver(Supplier<Archiver> delegate) {
    this.delegate = Suppliers.memoize(delegate);
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    delegate.get().appendToRuleKey(sink);
  }

  @Override
  public ImmutableList<FileScrubber> getScrubbers() {
    return delegate.get().getScrubbers();
  }

  @Override
  public boolean supportsThinArchives() {
    return delegate.get().supportsThinArchives();
  }

  @Override
  public ImmutableList<String> getArchiveOptions(boolean isThinArchive) {
    return delegate.get().getArchiveOptions(isThinArchive);
  }

  @Override
  public ImmutableList<String> outputArgs(String outputPath) {
    return delegate.get().outputArgs(outputPath);
  }

  @Override
  public boolean isRanLibStepRequired() {
    return delegate.get().isRanLibStepRequired();
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathRuleFinder ruleFinder) {
    return delegate.get().getDeps(ruleFinder);
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return delegate.get().getInputs();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return delegate.get().getCommandPrefix(resolver);
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return delegate.get().getEnvironment(resolver);
  }

  public Archiver getDelegate() {
    return delegate.get();
  }
}
