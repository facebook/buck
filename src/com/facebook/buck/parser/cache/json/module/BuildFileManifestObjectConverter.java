/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.parser.cache.json.module;

import com.facebook.buck.parser.api.BuildFileManifest;
import com.facebook.buck.skylark.io.GlobSpec;
import com.facebook.buck.skylark.io.GlobSpecWithResult;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.util.Optional;

/** A {@link BuildFileManifest} object serializer for the Jackson (json) format. */
public class BuildFileManifestObjectConverter extends JsonDeserializer<BuildFileManifest> {

  @SuppressWarnings({"unchecked"})
  private ImmutableMap<String, ImmutableMap<String, Object>> readTargets(
      JsonParser jsonParser, TreeNode treeNode) throws IOException {
    TreeNode targetsNode = treeNode.get("targets");
    JsonParser targetsParser = targetsNode.traverse();
    targetsParser.setCodec(jsonParser.getCodec());
    return targetsParser.readValueAs(ImmutableMap.class);
  }

  @SuppressWarnings({"unchecked"})
  private ImmutableList<String> readIncludes(JsonParser jsonParser, TreeNode treeNode)
      throws IOException {
    TreeNode includesNode = treeNode.get("includes");
    JsonParser includesParser = includesNode.traverse();
    includesParser.setCodec(jsonParser.getCodec());
    return includesParser.readValueAs(ImmutableList.class);
  }

  @SuppressWarnings({"unchecked"})
  private ImmutableMap<String, Object> readConfigs(JsonParser jsonParser, TreeNode treeNode)
      throws IOException {
    TreeNode configsNode = treeNode.get("configs");
    JsonParser configsParser = configsNode.traverse();
    configsParser.setCodec(jsonParser.getCodec());
    return configsParser.readValueAs(ImmutableMap.class);
  }

  @SuppressWarnings({"unchecked"})
  private ImmutableList<GlobSpecWithResult> readGlobManifest(
      JsonParser jsonParser, TreeNode treeNode) throws IOException {
    ImmutableList.Builder<GlobSpecWithResult> globs =
        ImmutableList.builderWithExpectedSize(treeNode.size());
    TreeNode globManifestRootNode = treeNode.get("globManifest");
    for (int i = 0; i < globManifestRootNode.size(); i++) {
      TreeNode globNode = globManifestRootNode.get(i);
      TreeNode globSpecNode = globNode.get("globSpec");
      TreeNode globSpecIncludeNode = globSpecNode.get("include");
      TreeNode globSpecExcludeNode = globSpecNode.get("exclude");
      TreeNode globSpecExcludeDirectoriesNode = globSpecNode.get("excludeDirectories");
      JsonParser globSpecIncludeParser = globSpecIncludeNode.traverse();
      JsonParser globSpecExcludeParser = globSpecExcludeNode.traverse();
      globSpecIncludeParser.setCodec(jsonParser.getCodec());
      ImmutableList<String> includes =
          globSpecIncludeParser.readValuesAs(ImmutableList.class).next();
      globSpecExcludeParser.setCodec(jsonParser.getCodec());
      ImmutableList<String> excludes =
          globSpecExcludeParser.readValuesAs(ImmutableList.class).next();
      boolean excludeDirs = Boolean.valueOf(globSpecExcludeDirectoriesNode.asToken().asString());
      GlobSpec globSpec =
          GlobSpec.builder()
              .setInclude(includes)
              .setExclude(excludes)
              .setExcludeDirectories(excludeDirs)
              .build();

      TreeNode globSpecFilePathsNode = globNode.get("filePaths");
      JsonParser globSpecFilePathsParser = globSpecFilePathsNode.traverse();
      globSpecFilePathsParser.setCodec(jsonParser.getCodec());
      ImmutableList<String> globManifestFilePaths =
          globSpecFilePathsParser.readValueAs(ImmutableList.class);
      globs.add(GlobSpecWithResult.of(globSpec, globManifestFilePaths));
    }

    return globs.build();
  }

  @Override
  public BuildFileManifest deserialize(
      JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
    TreeNode treeNode = jsonParser.readValueAsTree();

    ImmutableMap<String, ImmutableMap<String, Object>> targets = readTargets(jsonParser, treeNode);
    ImmutableList<String> includes = readIncludes(jsonParser, treeNode);
    ImmutableMap<String, Object> configs = readConfigs(jsonParser, treeNode);
    Preconditions.checkState(
        treeNode.get("env") == null || treeNode.get("env").size() == 0,
        "The env field of BuildFileManifest is expected to be always null.");
    ImmutableList<GlobSpecWithResult> globs = readGlobManifest(jsonParser, treeNode);

    return BuildFileManifest.of(targets, includes, configs, Optional.of(ImmutableMap.of()), globs);
  }
}
