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

package com.facebook.buck.apple;

import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import org.immutables.value.Value;

import java.util.Collection;
import java.util.List;

@Value.Immutable
@BuckStyleImmutable
public abstract class GroupedSource {
  /**
   * The type of grouped source entry this object represents.
   */
  public enum Type {
      /**
       * A single {@link SourcePath}.
       */
      SOURCE_PATH,
      /**
       * A source group (group name and one or more GroupedSource objects).
       */
      SOURCE_GROUP
  }

  @Value.Parameter
  public abstract Type getType();

  @Value.Parameter
  public abstract Optional<SourcePath> getSourcePath();

  @Value.Parameter
  public abstract Optional<String> getSourceGroupName();

  @Value.Parameter
  public abstract Optional<List<GroupedSource>> getSourceGroup();

  @Value.Check
  protected void check() {
    switch (getType()) {
      case SOURCE_PATH:
        Preconditions.checkArgument(getSourcePath().isPresent());
        Preconditions.checkArgument(!getSourceGroupName().isPresent());
        Preconditions.checkArgument(!getSourceGroup().isPresent());
        break;
      case SOURCE_GROUP:
        Preconditions.checkArgument(!getSourcePath().isPresent());
        Preconditions.checkArgument(getSourceGroupName().isPresent());
        Preconditions.checkArgument(getSourceGroup().isPresent());
        break;
      default:
        throw new RuntimeException("Unhandled type: " + getType());
    }
  }

  /**
   * Creates a {@link GroupedSource} given a {@link SourcePath}.
   */
  public static GroupedSource ofSourcePath(SourcePath sourcePath) {
    return ImmutableGroupedSource.of(
        Type.SOURCE_PATH,
        Optional.of(sourcePath),
        Optional.<String>absent(),
        Optional.<List<GroupedSource>>absent());
  }

  /**
   * Creates a {@link GroupedSource} given a source group name and a
   * list of GroupedSources.
   */
  public static GroupedSource ofSourceGroup(
      String sourceGroupName,
      Collection<GroupedSource> sourceGroup) {
    return ImmutableGroupedSource.of(
        Type.SOURCE_GROUP,
        Optional.<SourcePath>absent(),
        Optional.of(sourceGroupName),
        Optional.of((List<GroupedSource>) ImmutableList.copyOf(sourceGroup)));
  }

  public static interface Visitor {
    public void visitSourcePath(SourcePath sourcePath);
    public void visitSourceGroup(String sourceGroupName);
  }

  public void visit(Visitor visitor) {
    switch (this.getType()) {
    case SOURCE_PATH:
      visitor.visitSourcePath(getSourcePath().get());
      break;
    case SOURCE_GROUP:
      visitor.visitSourceGroup(getSourceGroupName().get());
      for (GroupedSource group : getSourceGroup().get()) {
        group.visit(visitor);
      }
      break;
    }
  }
}
