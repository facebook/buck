/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java.stepsbuilder.javacd.serialization;

import com.facebook.buck.javacd.model.JavaAbiInfo;
import com.facebook.buck.jvm.core.BaseJavaAbiInfo;
import com.facebook.buck.jvm.core.DefaultBaseJavaAbiInfo;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** {@link DefaultBaseJavaAbiInfo} to protobuf serializer */
public class JavaAbiInfoSerializer {

  private JavaAbiInfoSerializer() {}

  /** Serializes {@link BaseJavaAbiInfo} into {@link JavaAbiInfo} */
  public static JavaAbiInfo toJavaAbiInfo(BaseJavaAbiInfo javaAbiInfo) {
    Preconditions.checkState(javaAbiInfo instanceof DefaultBaseJavaAbiInfo);
    return serialize((DefaultBaseJavaAbiInfo) javaAbiInfo);
  }

  /** Deserializes list of {@link JavaAbiInfo} into list of {@link BaseJavaAbiInfo} */
  public static ImmutableList<BaseJavaAbiInfo> toJavaAbiInfo(List<JavaAbiInfo> list) {
    ImmutableList.Builder<BaseJavaAbiInfo> builder =
        ImmutableList.builderWithExpectedSize(list.size());
    for (JavaAbiInfo item : list) {
      builder.add(deserialize(item));
    }
    return builder.build();
  }

  /** Serializes {@link DefaultBaseJavaAbiInfo} into javacd model's {@link JavaAbiInfo}. */
  public static JavaAbiInfo serialize(DefaultBaseJavaAbiInfo javaAbiInfo) {
    JavaAbiInfo.Builder builder = JavaAbiInfo.newBuilder();

    builder.setBuildTargetName(javaAbiInfo.getUnflavoredBuildTargetName());
    ImmutableSet<Path> contentPaths = javaAbiInfo.getContentPaths();
    for (Path content : Optional.ofNullable(contentPaths).orElse(ImmutableSet.of())) {
      builder.addContentPaths(PathSerializer.serialize(content));
    }

    return builder.build();
  }

  /** Deserializes javacd model's {@link JavaAbiInfo} into {@link DefaultBaseJavaAbiInfo}. */
  public static DefaultBaseJavaAbiInfo deserialize(JavaAbiInfo javaAbiInfo) {
    DefaultBaseJavaAbiInfo defaultBaseJavaAbiInfo =
        new DefaultBaseJavaAbiInfo(javaAbiInfo.getBuildTargetName());
    List<com.facebook.buck.javacd.model.Path> contentPathsList = javaAbiInfo.getContentPathsList();
    if (!contentPathsList.isEmpty()) {
      defaultBaseJavaAbiInfo.setContentPaths(
          contentPathsList.stream()
              .map(PathSerializer::deserialize)
              .collect(ImmutableSet.toImmutableSet()));
    }
    return defaultBaseJavaAbiInfo;
  }
}
