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
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.Collection;
import java.util.Objects;

import javax.annotation.Nullable;

public class GroupedSource {
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
  };

  private final Type type;
  @Nullable private final SourcePath sourcePath;
  @Nullable private final String sourceGroupName;
  @Nullable private final ImmutableList<GroupedSource> sourceGroup;

  private GroupedSource(
      Type type,
      @Nullable SourcePath sourcePath,
      @Nullable String sourceGroupName,
      @Nullable ImmutableList<GroupedSource> sourceGroup) {
    this.type = type;
    switch (this.type) {
      case SOURCE_PATH:
        Preconditions.checkNotNull(sourcePath);
        Preconditions.checkArgument(sourceGroupName == null);
        Preconditions.checkArgument(sourceGroup == null);
        break;
      case SOURCE_GROUP:
        Preconditions.checkArgument(sourcePath == null);
        Preconditions.checkNotNull(sourceGroupName);
        Preconditions.checkNotNull(sourceGroup);
        break;
      default:
        throw new RuntimeException("Unhandled type: " + type);
    }
    this.sourcePath = sourcePath;
    this.sourceGroupName = sourceGroupName;
    this.sourceGroup = sourceGroup;
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof GroupedSource) {
      GroupedSource that = (GroupedSource) other;
      return Objects.equals(this.type, that.type) &&
          Objects.equals(this.sourcePath, that.sourcePath) &&
          Objects.equals(this.sourceGroupName, that.sourceGroupName) &&
          Objects.equals(this.sourceGroup, that.sourceGroup);
    }
    return false;
  }

  public Type getType() {
    return type;
  }

  public SourcePath getSourcePath() {
    return Preconditions.checkNotNull(sourcePath);
  }

  public String getSourceGroupName() {
    return Preconditions.checkNotNull(sourceGroupName);
  }

  public ImmutableList<GroupedSource> getSourceGroup() {
    return Preconditions.checkNotNull(sourceGroup);
  }

  /**
   * Creates a {@link GroupedSource} given a {@link SourcePath}.
   */
  public static GroupedSource ofSourcePath(SourcePath sourcePath) {
    return new GroupedSource(Type.SOURCE_PATH, sourcePath, null, null);
  }

  /**
   * Creates a {@link GroupedSource} given a source group name and a
   * list of GroupedSources.
   */
  public static GroupedSource ofSourceGroup(
      String sourceGroupName,
      Collection<GroupedSource> sourceGroup) {
    return new GroupedSource(
        Type.SOURCE_GROUP,
        null,
        sourceGroupName,
        ImmutableList.copyOf(sourceGroup));
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, sourcePath, sourceGroupName, sourceGroup);
  }

  public static interface Visitor {
    public void visitSourcePath(SourcePath sourcePath);
    public void visitSourceGroup(String sourceGroupName);
  }

  public void visit(Visitor visitor) {
    switch (this.getType()) {
    case SOURCE_PATH:
      visitor.visitSourcePath(Preconditions.checkNotNull(sourcePath));
      break;
    case SOURCE_GROUP:
      visitor.visitSourceGroup(Preconditions.checkNotNull(sourceGroupName));
      for (GroupedSource group : Preconditions.checkNotNull(sourceGroup)) {
        group.visit(visitor);
      }
      break;
    }
  }
}
