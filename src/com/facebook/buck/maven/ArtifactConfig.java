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

package com.facebook.buck.maven;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ArtifactConfig {

  @VisibleForTesting
  static class CmdLineArgs {
    @Argument(usage = "One or more artifacts to resolve", metaVar = "artifact")
    public List<String> artifacts = new ArrayList<>();

    @Option(name = "-repo", usage = "Root of your repository")
    public String buckRepoRoot;

    @Option(name = "-third-party", usage = "Directory to place dependencies in")
    public String thirdParty = "third-party";

    @Option(name = "-local-maven", usage = "Local Maven repository")
    public String mavenLocalRepo;

    @Option(name = "-maven", usage = "Maven URI(s)")
    public List<String> repositoryURIs = new ArrayList<>();

    @Option(name = "-visibility", usage = "Targets that can see the artifacts. (PUBLIC is allowed)")
    public List<String> visibility = new ArrayList<>();

    @Option(name = "-json", usage = "JSON configuration file for artifacts, paths, and Maven repos")
    public String artifactConfigJson;

    @Option(name = "-help", help = true)
    public boolean showHelp;
  }

  public static class Repository {
    public String url;
    public String user;
    public String password;

    public Repository() {}

    public Repository(String url) {
      this.url = url;
    }
  }

  public List<Repository> repositories = new ArrayList<>();

  public List<String> artifacts = new ArrayList<>();

  @JsonProperty("repo")
  public String buckRepoRoot;

  @JsonProperty("third_party")
  public String thirdParty = "third-party";

  @JsonProperty("local_maven")
  public String mavenLocalRepo =
      Paths.get(System.getProperty("user.home"), ".m2", "repository").toAbsolutePath().toString();

  public List<String> visibility = new ArrayList<>();

  public ArtifactConfig mergeCmdLineArgs(CmdLineArgs args) {
    buckRepoRoot = MoreObjects.firstNonNull(args.buckRepoRoot, buckRepoRoot);

    mavenLocalRepo = MoreObjects.firstNonNull(args.mavenLocalRepo, mavenLocalRepo);
    thirdParty = MoreObjects.firstNonNull(args.thirdParty, thirdParty);

    artifacts.addAll(args.artifacts);

    visibility.addAll(args.visibility);

    for (String url : args.repositoryURIs) {
      repositories.add(new Repository(url));
    }

    return this;
  }

  private static void usage(CmdLineParser parser) {
    System.out.println("Import Maven JARs as Buck build rules.");
    System.out.println();
    System.out.println("Usage: java -jar resolver.jar [OPTIONS] -repo REPO artifact...");
    System.out.println();
    System.out.println(
        "Artifacts are of the form group:artifact[:extension[:classifier]]:version, " +
            "or a .pom file");
    parser.printUsage(System.out);
    System.exit(0);
  }

  public static ArtifactConfig fromCommandLineArgs(
      String[] args) throws CmdLineException, IOException {

    CmdLineArgs parsedArgs = new CmdLineArgs();
    CmdLineParser parser = new CmdLineParser(parsedArgs);
    parser.parseArgument(args);

    if (parsedArgs.showHelp) {
      usage(parser);
    }

    ArtifactConfig artifactConfig;

    // If the -config argument was specified, load a config from JSON.
    if (parsedArgs.artifactConfigJson != null) {
      ObjectMapper mapper = new ObjectMapper();
      File jsonFile = new File(parsedArgs.artifactConfigJson);
      artifactConfig = mapper.readValue(jsonFile, ArtifactConfig.class);
    } else {
      artifactConfig = new ArtifactConfig();
    }

    if (artifactConfig.buckRepoRoot == null && parsedArgs.buckRepoRoot == null) {
      usage(parser);
    }

    artifactConfig.mergeCmdLineArgs(parsedArgs);

    return artifactConfig;
  }
}
