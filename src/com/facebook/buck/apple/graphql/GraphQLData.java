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

package com.facebook.buck.apple.graphql;

import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.DefaultShellStep;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.StringTemplateStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.GsonBuilder;

import org.stringtemplate.v4.ST;

import java.nio.file.Path;
import java.nio.file.Paths;

public class GraphQLData extends AbstractBuildRule {

  private static final Path PATH_TO_GRAPH_QL_BATCH_TEMPLATE = Paths.get(
      System.getProperty(
          "buck.path_to_graphql_batch_template",
          "src/com/facebook/buck/apple/graphql/graphql_batch_template"));

  private static final String BATCH_NAME = "fbmogen.batch";

  private final Function<Path, Path> pathAbsolutifier;
  private final GraphQLGenerationMode mode;
  private final SourcePath modelGenerator;
  private final ImmutableSet<SourcePath> queries;
  private final SourcePath consistencyConfig;
  private final SourcePath clientSchemaConfig;
  private final SourcePath schema;
  private final SourcePath mutations;
  private final ImmutableSet<GraphQLModelTag> modelTags;
  private final SourcePath knownIssuesFile;
  private final SourcePath persistIds;

  GraphQLData(
      BuildRuleParams params,
      SourcePathResolver resolver,
      Function<Path, Path> pathAbsolutifier,
      SourcePath modelGenerator,
      GraphQLGenerationMode mode,
      ImmutableSet<SourcePath> queries,
      SourcePath consistencyConfig,
      SourcePath clientSchemaConfig,
      SourcePath schema,
      SourcePath mutations,
      ImmutableSet<GraphQLModelTag> modelTags,
      SourcePath knownIssuesFile,
      SourcePath persistIds) {
    super(params, resolver);
    this.pathAbsolutifier = pathAbsolutifier;
    this.mode = mode;
    this.modelGenerator = modelGenerator;
    this.queries = queries;
    this.consistencyConfig = consistencyConfig;
    this.clientSchemaConfig = clientSchemaConfig;
    this.schema = schema;
    this.mutations = mutations;
    this.modelTags = modelTags;
    this.knownIssuesFile = knownIssuesFile;
    this.persistIds = persistIds;
  }

  @Override
  protected ImmutableCollection<Path> getInputsToCompareToOutput() {
    ImmutableSet<SourcePath> sourcePaths = ImmutableSet
        .<SourcePath>builder()
        .add(modelGenerator)
        .addAll(queries)
        .add(consistencyConfig)
        .add(clientSchemaConfig)
        .add(schema)
        .add(mutations)
        .add(knownIssuesFile)
        .add(persistIds)
        .build();

    return getResolver().filterInputsToCompareToOutput(sourcePaths);
  }

  @Override
  protected RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setReflectively("mode", mode)
        .setReflectively("modelTags", modelTags);
  }

  private Step writeDefaultFiles(Path workingDir) {
    String defaultFilesName = "defaultFiles.json";
    String hydrateFileName = "schema.json.hydrate";
    String localSchemaFileName = "localSchema.json";

    ImmutableMap<String, String> values = ImmutableMap.<String, String>builder()
        .put(
            "consistencyConfig",
            workingDir.relativize(getResolver().getPath(consistencyConfig)).toString())
        .put("config", workingDir.relativize(getResolver().getPath(clientSchemaConfig)).toString())
        .put("schema", workingDir.relativize(getResolver().getPath(schema)).toString())
        .put("hydrate", hydrateFileName)
        .put("localSchema", localSchemaFileName)
        .put("mutation", workingDir.relativize(getResolver().getPath(mutations)).toString())
        .build();

    String defaultFiles = new GsonBuilder().create().toJson(values);

    return new WriteFileStep(defaultFiles, workingDir.resolve(defaultFilesName));
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context,
      BuildableContext buildableContext) {
    final Path workingDir = BuildTargets.getBinPath(getBuildTarget(), "%s");
    final Path outputDir = BuildTargets.getGenPath(getBuildTarget(), "%s");
    buildableContext.recordArtifactsInDirectory(outputDir);

    ImmutableList.Builder<Step> builder = ImmutableList.builder();

    builder.add(new MakeCleanDirectoryStep(workingDir));
    builder.add(writeDefaultFiles(workingDir));
    builder.add(
        new StringTemplateStep(
            PATH_TO_GRAPH_QL_BATCH_TEMPLATE,
            workingDir.resolve(BATCH_NAME),
            new Function<ST, ST>() {
              @Override
              public ST apply(ST input) {
                return input
                    .add("for_linking", mode == GraphQLGenerationMode.FOR_LINKING)
                    .add("output_dir", workingDir.relativize(outputDir))
                    .add("model_tags", modelTags)
                    .add("persist_ids", workingDir.relativize(getResolver().getPath(persistIds)));
              }
            }));

    Path modelGenerator = getResolver().getPath(this.modelGenerator);
    Path consistencyConfig = getResolver().getPath(this.consistencyConfig);
    Path knownIssuesFile = getResolver().getPath(this.knownIssuesFile);
    ImmutableList<String> queries = FluentIterable
        .from(this.queries)
        .transform(
            new Function<SourcePath, String>() {
              @Override
              public String apply(SourcePath input) {
                return workingDir.relativize(getResolver().getPath(input)).toString();
              }
            })
        .toList();

    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add(
        workingDir.relativize(modelGenerator).toString(),
        "run-objc-batch",
        "--known-issues-file",
        workingDir.relativize(knownIssuesFile).toString(),
        "-B",
        BATCH_NAME,
        "-x",
        workingDir.relativize(consistencyConfig).toString());
    args.addAll(queries);

    builder.add(new DefaultShellStep(pathAbsolutifier.apply(workingDir), args.build()));

    return builder.build();
  }

  @Override
  public Path getPathToOutputFile() {
    return BuildTargets.getGenPath(getBuildTarget(), "lib%s.a");
  }
}
