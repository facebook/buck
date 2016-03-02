package com.facebook.buck.maven;

import com.google.common.base.Charsets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ArtifactConfig {

  private static class CmdLineArgs {
    @Argument(usage="Artifacts to resolve", metaVar="artifact")
    public List<String> artifacts = new ArrayList<>();

    @Option(name="-repo", usage="Buck repository root")
    public String buckRepoRoot;

    @Option(name="-third-party", usage="relative third-party path")
    public String thirdParty = "third-party";

    @Option(name="-local-maven", usage="Local Maven repository")
    public String mavenLocalRepo;

    @Option(name="-maven", usage="Maven URI")
    public List<String> repositoryURIs = new ArrayList<>();

    @Option(name="-config", usage="Artifact config JSON")
    public String artifactConfigJson;
  }

  public static class Repository {
    public String url;
    public String user;
    public String password;

    public Repository(String url) {
      this.url = url;
    }
  }

  public List<Repository> repositories = new ArrayList<>();

  public List<String> artifacts = new ArrayList<>();

  @SerializedName("buck_repo")
  public String buckRepoRoot;

  @SerializedName("third_party")
  public String thirdParty = "third-party";

  @SerializedName("local_maven")
  public String mavenLocalRepo;

  public static ArtifactConfig fromCommandLineArgs(
      String[] args) throws CmdLineException, IOException {

    CmdLineArgs parsedArgs = new CmdLineArgs();
    CmdLineParser parser = new CmdLineParser(parsedArgs);
    parser.parseArgument(args);

    ArtifactConfig artifactConfig;

    // If the -config argument was specified, load a config from JSON.
    if (parsedArgs.artifactConfigJson != null) {
      Gson gson = new GsonBuilder().create();
      String jsonData = com.google.common.io.Files.toString(
          new File(parsedArgs.artifactConfigJson), Charsets.UTF_8);
      artifactConfig = gson.fromJson(jsonData, ArtifactConfig.class);
    } else {
      artifactConfig = new ArtifactConfig();
    }

    // Merge command line arguments into ArtifactConfig.
    if (parsedArgs.buckRepoRoot != null) {
      artifactConfig.buckRepoRoot = parsedArgs.buckRepoRoot;
    }

    if (parsedArgs.mavenLocalRepo != null) {
      artifactConfig.mavenLocalRepo = parsedArgs.mavenLocalRepo;
    }

    if (parsedArgs.thirdParty != null) {
      artifactConfig.thirdParty = parsedArgs.thirdParty;
    }

    artifactConfig.artifacts.addAll(parsedArgs.artifacts);
    for (String url : parsedArgs.repositoryURIs) {
      artifactConfig.repositories.add(new Repository(url));
    }

    return artifactConfig;
  }
}
