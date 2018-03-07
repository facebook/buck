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

package com.facebook.buck.maven;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.aether.util.artifact.JavaScopes.TEST;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.graph.TraversableGraph;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.util.concurrent.MostExecutors;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.io.Resources;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.composition.DefaultDependencyManagementImporter;
import org.apache.maven.model.management.DefaultDependencyManagementInjector;
import org.apache.maven.model.management.DefaultPluginManagementInjector;
import org.apache.maven.model.plugin.DefaultPluginConfigurationExpander;
import org.apache.maven.model.profile.DefaultProfileSelector;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.ArtifactProperties;
import org.eclipse.aether.artifact.ArtifactType;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.artifact.DefaultArtifactType;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.locator.ServiceLocator;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.artifact.SubArtifact;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;
import org.eclipse.aether.version.VersionScheme;
import org.kohsuke.args4j.CmdLineException;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroupString;

public class Resolver {

  private static final String TEMPLATE =
      Resolver.class.getPackage().getName().replace('.', '/') + "/build-file.st";
  public static final String ARTIFACT_FILE_NAME_FORMAT = "%s-%s.%s";
  public static final String ARTIFACT_FILE_NAME_REGEX_FORMAT =
      ARTIFACT_FILE_NAME_FORMAT.replace(".", "\\.");
  public static final String VERSION_REGEX_GROUP = "([^-]+)";

  private final Path buckRepoRoot;
  private final Path buckThirdPartyRelativePath;
  private final LocalRepository localRepo;
  private final RepositorySystem repoSys;
  private final RepositorySystemSession session;

  private final VersionScheme versionScheme = new GenericVersionScheme();
  private final List<String> visibility;
  private final ModelBuilder modelBuilder;

  private ImmutableList<RemoteRepository> repos;

  public Resolver(ArtifactConfig config) {
    this.modelBuilder =
        new DefaultModelBuilderFactory()
            .newInstance()
            .setProfileSelector(new DefaultProfileSelector())
            .setPluginConfigurationExpander(new DefaultPluginConfigurationExpander())
            .setPluginManagementInjector(new DefaultPluginManagementInjector())
            .setDependencyManagementImporter(new DefaultDependencyManagementImporter())
            .setDependencyManagementInjector(new DefaultDependencyManagementInjector());
    ServiceLocator locator = AetherUtil.initServiceLocator();
    this.repoSys = locator.getService(RepositorySystem.class);
    this.localRepo = new LocalRepository(Paths.get(config.mavenLocalRepo).toFile());
    this.session = newSession(repoSys, localRepo);

    this.buckRepoRoot = Paths.get(Preconditions.checkNotNull(config.buckRepoRoot));
    this.buckThirdPartyRelativePath = Paths.get(Preconditions.checkNotNull(config.thirdParty));
    this.visibility = config.visibility;

    this.repos =
        config
            .repositories
            .stream()
            .map(AetherUtil::toRemoteRepository)
            .collect(ImmutableList.toImmutableList());
  }

  public void resolve(Collection<String> artifacts)
      throws RepositoryException, ExecutionException, InterruptedException, IOException {
    ImmutableList.Builder<RemoteRepository> repoBuilder = ImmutableList.builder();
    ImmutableMap.Builder<String, Dependency> dependencyBuilder = ImmutableMap.builder();

    repoBuilder.addAll(repos);
    for (String artifact : artifacts) {
      if (artifact.endsWith(".pom")) {
        Model model = loadPomModel(Paths.get(artifact));
        repoBuilder.addAll(getReposFromPom(model));
        for (Dependency dep : getDependenciesFromPom(model)) {
          dependencyBuilder.put(buildKey(dep.getArtifact()), dep);
        }
      } else {
        Dependency dep = getDependencyFromString(artifact);
        dependencyBuilder.put(buildKey(dep.getArtifact()), dep);
      }
    }
    repos = repoBuilder.build();
    ImmutableMap<String, Dependency> specifiedDependencies = dependencyBuilder.build();

    ImmutableMap<String, Artifact> knownDeps =
        getRunTimeTransitiveDeps(specifiedDependencies.values());

    // We now have the complete set of dependencies. Build the graph of dependencies. We'd like
    // aether to do this for us, but it doesn't preserve the complete dependency information we need
    // to accurately construct build files.
    MutableDirectedGraph<Artifact> graph = buildDependencyGraph(knownDeps);

    // Now we have the graph, grab the sources and jars for each dependency, as well as the relevant
    // checksums (which are download by default. Yay!)
    ImmutableSetMultimap<Path, Prebuilt> downloadedArtifacts =
        downloadArtifacts(graph, specifiedDependencies);

    createBuckFiles(downloadedArtifacts);
  }

  private ImmutableSetMultimap<Path, Prebuilt> downloadArtifacts(
      MutableDirectedGraph<Artifact> graph, ImmutableMap<String, Dependency> specifiedDependencies)
      throws ExecutionException, InterruptedException {
    ListeningExecutorService exec =
        MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors(),
                new MostExecutors.NamedThreadFactory("artifact download")));

    @SuppressWarnings("unchecked")
    List<ListenableFuture<Map.Entry<Path, Prebuilt>>> results =
        (List<ListenableFuture<Map.Entry<Path, Prebuilt>>>)
            (List<?>)
                exec.invokeAll(
                    graph
                        .getNodes()
                        .stream()
                        .map(
                            artifact ->
                                (Callable<Map.Entry<Path, Prebuilt>>)
                                    () -> downloadArtifact(artifact, graph, specifiedDependencies))
                        .collect(ImmutableList.toImmutableList()));

    try {
      return ImmutableSetMultimap.<Path, Prebuilt>builder()
          .orderValuesBy(Ordering.natural())
          .putAll(Futures.allAsList(results).get())
          .build();
    } finally {
      exec.shutdown();
    }
  }

  private Map.Entry<Path, Prebuilt> downloadArtifact(
      Artifact artifactToDownload,
      TraversableGraph<Artifact> graph,
      ImmutableMap<String, Dependency> specifiedDependencies)
      throws IOException, ArtifactResolutionException {
    String projectName = getProjectName(artifactToDownload);
    Path project = buckRepoRoot.resolve(buckThirdPartyRelativePath).resolve(projectName);
    Files.createDirectories(project);

    Prebuilt library = resolveLib(artifactToDownload, project);

    // Populate deps
    Iterable<Artifact> incoming = graph.getIncomingNodesFor(artifactToDownload);
    for (Artifact artifact : incoming) {
      String groupName = getProjectName(artifact);
      if (projectName.equals(groupName)) {
        library.addDep(String.format(":%s", artifact.getArtifactId()));
      } else {
        library.addDep(buckThirdPartyRelativePath, artifact);
      }
    }

    // Populate visibility
    Iterable<Artifact> outgoing = graph.getOutgoingNodesFor(artifactToDownload);
    for (Artifact artifact : outgoing) {
      String groupName = getProjectName(artifact);
      if (!groupName.equals(projectName)) {
        library.addVisibility(buckThirdPartyRelativePath, artifact);
      }
    }

    if (specifiedDependencies.containsKey(buildKey(artifactToDownload))) {
      for (String rule : visibility) {
        library.addVisibility(rule);
      }
    }
    return Maps.immutableEntry(project, library);
  }

  private Prebuilt resolveLib(Artifact artifact, Path project)
      throws ArtifactResolutionException, IOException {
    Artifact jar =
        new DefaultArtifact(
            artifact.getGroupId(), artifact.getArtifactId(), "jar", artifact.getVersion());

    Path relativePath = resolveArtifact(jar, project);

    Prebuilt library = new Prebuilt(jar.getArtifactId(), jar.toString(), relativePath);

    downloadSources(jar, project, library);
    return library;
  }

  /** @return {@link Path} to the file */
  private Path resolveArtifact(Artifact artifact, Path project)
      throws ArtifactResolutionException, IOException {
    Optional<Path> newerVersionFile = getNewerVersionFile(artifact, project);
    if (newerVersionFile.isPresent()) {
      return newerVersionFile.get();
    }
    ArtifactResult result =
        repoSys.resolveArtifact(session, new ArtifactRequest(artifact, repos, null));
    return copy(result, project);
  }

  /**
   * @return {@link Path} to the file in {@code project} with filename consistent with the given
   *     {@link Artifact}, but with a newer version. If no such file exists, {@link Optional#empty}
   *     is returned. If multiple such files are present one with the newest version will be
   *     returned.
   */
  @VisibleForTesting
  Optional<Path> getNewerVersionFile(Artifact artifactToDownload, Path project) throws IOException {
    Version artifactToDownloadVersion;
    try {
      artifactToDownloadVersion = versionScheme.parseVersion(artifactToDownload.getVersion());
    } catch (InvalidVersionSpecificationException e) {
      throw new RuntimeException(e);
    }

    Pattern versionExtractor =
        Pattern.compile(
            String.format(
                ARTIFACT_FILE_NAME_REGEX_FORMAT,
                artifactToDownload.getArtifactId(),
                VERSION_REGEX_GROUP,
                artifactToDownload.getExtension()));
    Iterable<Version> versionsPresent =
        FluentIterable.from(Files.newDirectoryStream(project))
            .transform(
                new Function<Path, Version>() {
                      @Nullable
                      @Override
                      public Version apply(Path input) {
                        Matcher matcher = versionExtractor.matcher(input.getFileName().toString());
                        if (matcher.matches()) {
                          try {
                            return versionScheme.parseVersion(matcher.group(1));
                          } catch (InvalidVersionSpecificationException e) {
                            throw new RuntimeException(e);
                          }
                        } else {
                          return null;
                        }
                      }
                    }
                    ::apply)
            .filter(Objects::nonNull);

    List<Version> newestPresent = Ordering.natural().greatestOf(versionsPresent, 1);
    if (newestPresent.isEmpty() || newestPresent.get(0).compareTo(artifactToDownloadVersion) <= 0) {
      return Optional.empty();
    } else {
      return Optional.of(
          project.resolve(
              String.format(
                  ARTIFACT_FILE_NAME_FORMAT,
                  artifactToDownload.getArtifactId(),
                  newestPresent.get(0).toString(),
                  artifactToDownload.getExtension())));
    }
  }

  private void downloadSources(Artifact artifact, Path project, Prebuilt library)
      throws IOException {
    Artifact srcs = new SubArtifact(artifact, "sources", "jar");
    try {
      Path relativePath = resolveArtifact(srcs, project);
      library.setSourceJar(relativePath);
    } catch (ArtifactResolutionException e) {
      System.err.println("Skipping sources for: " + srcs);
    }
  }

  /** com.example:foo:1.0 -> "example" */
  private static String getProjectName(Artifact artifact) {
    int index = artifact.getGroupId().lastIndexOf('.');
    String projectName = artifact.getGroupId();
    if (index != -1) {
      projectName = projectName.substring(index + 1);
    }
    return projectName;
  }

  private void createBuckFiles(ImmutableSetMultimap<Path, Prebuilt> buckFilesData)
      throws IOException {
    URL templateUrl = Resources.getResource(TEMPLATE);
    String template = Resources.toString(templateUrl, UTF_8);
    STGroupString groups = new STGroupString("prebuilt-template", template);

    for (Path key : buckFilesData.keySet()) {
      Path buckFile = key.resolve("BUCK");
      if (Files.exists(buckFile)) {
        Files.delete(buckFile);
      }

      ST st = Preconditions.checkNotNull(groups.getInstanceOf("/prebuilts"));
      st.add("data", buckFilesData.get(key));
      Files.write(buckFile, st.render().getBytes(UTF_8));
    }
  }

  private Path copy(ArtifactResult result, Path destDir) throws IOException {
    Path source = result.getArtifact().getFile().toPath();
    Path sink = destDir.resolve(source.getFileName());

    if (!Files.exists(sink)) {
      Files.copy(source, sink);
    }

    return sink.getFileName();
  }

  private MutableDirectedGraph<Artifact> buildDependencyGraph(Map<String, Artifact> knownDeps)
      throws ArtifactDescriptorException {
    MutableDirectedGraph<Artifact> graph;
    graph = new MutableDirectedGraph<>();
    for (Map.Entry<String, Artifact> entry : knownDeps.entrySet()) {
      String key = entry.getKey();
      Artifact artifact = entry.getValue();

      graph.addNode(artifact);

      List<Dependency> dependencies = getDependenciesOf(artifact);

      for (Dependency dependency : dependencies) {
        if (dependency.getArtifact() == null) {
          System.out.println("Skipping because artifact missing: " + dependency);
          continue;
        }

        String depKey = buildKey(dependency.getArtifact());
        Artifact actualDep = knownDeps.get(depKey);
        if (actualDep == null) {
          continue;
        }
        // It's possible that the runtime dep of an artifact is the test time dep of another.
        if (isTestTime(dependency)) {
          continue;
        }

        // TODO(simons): Do we always want optional dependencies?
        //        if (dependency.isOptional()) {
        //          continue;
        //        }

        Preconditions.checkNotNull(
            actualDep, key + " -> " + artifact + " in " + knownDeps.keySet());
        graph.addNode(actualDep);
        graph.addEdge(actualDep, artifact);
      }
    }
    return graph;
  }

  private List<Dependency> getDependenciesOf(Artifact dep) throws ArtifactDescriptorException {
    ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest();
    descriptorRequest.setArtifact(dep);
    descriptorRequest.setRepositories(repos);
    descriptorRequest.setRequestContext(JavaScopes.RUNTIME);

    ArtifactDescriptorResult result = repoSys.readArtifactDescriptor(session, descriptorRequest);
    return result.getDependencies();
  }

  private boolean isTestTime(Dependency dependency) {
    return TEST.equals(dependency.getScope());
  }

  private Model loadPomModel(Path pomFile) {
    DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();
    request.setPomFile(pomFile.toFile());
    try {
      ModelBuildingResult result = modelBuilder.build(request);
      return result.getRawModel();
    } catch (ModelBuildingException | IllegalArgumentException e) {
      // IllegalArg can be thrown if the parent POM cannot be resolved.
      throw new RuntimeException(e);
    }
  }

  private ImmutableList<RemoteRepository> getReposFromPom(Model model) {
    return model
        .getRepositories()
        .stream()
        .map(
            input ->
                new RemoteRepository.Builder(input.getId(), input.getLayout(), input.getUrl())
                    .setReleasePolicy(toPolicy(input.getReleases()))
                    .setSnapshotPolicy(toPolicy(input.getSnapshots()))
                    .build())
        .collect(ImmutableList.toImmutableList());
  }

  @Nullable
  private RepositoryPolicy toPolicy(org.apache.maven.model.RepositoryPolicy policy) {
    if (policy != null) {
      return new RepositoryPolicy(
          policy.isEnabled(), policy.getUpdatePolicy(), policy.getChecksumPolicy());
    }
    return null;
  }

  private ImmutableList<Dependency> getDependenciesFromPom(Model model) {
    return model
        .getDependencies()
        .stream()
        .map(
            dep -> {
              ArtifactType stereotype = session.getArtifactTypeRegistry().get(dep.getType());
              if (stereotype == null) {
                stereotype = new DefaultArtifactType(dep.getType());
              }

              Map<String, String> props = null;
              boolean system = dep.getSystemPath() != null && dep.getSystemPath().length() > 0;
              if (system) {
                props = ImmutableMap.of(ArtifactProperties.LOCAL_PATH, dep.getSystemPath());
              }

              @SuppressWarnings("PMD.PrematureDeclaration")
              DefaultArtifact artifact =
                  new DefaultArtifact(
                      dep.getGroupId(),
                      dep.getArtifactId(),
                      dep.getClassifier(),
                      null,
                      dep.getVersion(),
                      props,
                      stereotype);

              ImmutableList<Exclusion> exclusions =
                  FluentIterable.from(dep.getExclusions())
                      .transform(
                          input -> {
                            String group = input.getGroupId();
                            String artifact1 = input.getArtifactId();

                            group = (group == null || group.length() == 0) ? "*" : group;
                            artifact1 =
                                (artifact1 == null || artifact1.length() == 0) ? "*" : artifact1;

                            return new Exclusion(group, artifact1, "*", "*");
                          })
                      .toList();
              return new Dependency(artifact, dep.getScope(), dep.isOptional(), exclusions);
            })
        .collect(ImmutableList.toImmutableList());
  }

  private Dependency getDependencyFromString(String artifact) {
    return new Dependency(new DefaultArtifact(artifact), JavaScopes.RUNTIME);
  }

  private ImmutableMap<String, Artifact> getRunTimeTransitiveDeps(Iterable<Dependency> mavenCoords)
      throws RepositoryException {

    CollectRequest collectRequest = new CollectRequest();
    collectRequest.setRequestContext(JavaScopes.RUNTIME);
    collectRequest.setRepositories(repos);

    for (Dependency dep : mavenCoords) {
      collectRequest.addDependency(dep);
    }

    DependencyFilter filter = DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME);
    DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, filter);

    DependencyResult dependencyResult = repoSys.resolveDependencies(session, dependencyRequest);

    ImmutableSortedMap.Builder<String, Artifact> knownDeps = ImmutableSortedMap.naturalOrder();
    for (ArtifactResult artifactResult : dependencyResult.getArtifactResults()) {
      Artifact node = artifactResult.getArtifact();
      knownDeps.put(buildKey(node), node);
    }
    return knownDeps.build();
  }

  private static RepositorySystemSession newSession(
      RepositorySystem repoSys, LocalRepository localRepo) {
    DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

    session.setLocalRepositoryManager(repoSys.newLocalRepositoryManager(session, localRepo));
    session.setReadOnly();

    return session;
  }

  /** Construct a key to identify the artifact, less its version */
  private String buildKey(Artifact artifact) {
    return artifact.getGroupId()
        + ':'
        + artifact.getArtifactId()
        + ':'
        + artifact.getExtension()
        + ':'
        + artifact.getClassifier();
  }

  public static void main(String[] args)
      throws CmdLineException, RepositoryException, IOException, ExecutionException,
          InterruptedException {
    ArtifactConfig artifactConfig = ArtifactConfig.fromCommandLineArgs(args);
    new Resolver(artifactConfig).resolve(artifactConfig.artifacts);
  }

  /** Holds data for creation of a BUCK file for a given dependency */
  private static class Prebuilt implements Comparable<Prebuilt> {
    private static final String PUBLIC_VISIBILITY = "PUBLIC";

    private final String name;
    private final String mavenCoords;
    private final Path binaryJar;
    @Nullable private Path sourceJar;
    private final SortedSet<String> deps = new TreeSet<>(new BuckDepComparator());
    private final SortedSet<String> visibilities = new TreeSet<>(new BuckDepComparator());
    private boolean publicVisibility = false;

    public Prebuilt(String name, String mavenCoords, Path binaryJar) {
      this.name = name;
      this.mavenCoords = mavenCoords;
      this.binaryJar = binaryJar;
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public String getName() {
      return name;
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public String getMavenCoords() {
      return mavenCoords;
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public Path getBinaryJar() {
      return binaryJar;
    }

    public void setSourceJar(Path sourceJar) {
      this.sourceJar = sourceJar;
    }

    @Nullable
    public Path getSourceJar() {
      return sourceJar;
    }

    public void addDep(String dep) {
      this.deps.add(dep);
    }

    public void addDep(Path buckThirdPartyRelativePath, Artifact artifact) {
      this.addDep(formatDep(buckThirdPartyRelativePath, artifact));
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public SortedSet<String> getDeps() {
      return deps;
    }

    public void addVisibility(String dep) {
      if (PUBLIC_VISIBILITY.equals(dep)) {
        publicVisibility = true;
      } else {
        this.visibilities.add(dep);
      }
    }

    public void addVisibility(Path buckThirdPartyRelativePath, Artifact artifact) {
      this.addVisibility(formatDep(buckThirdPartyRelativePath, artifact));
    }

    private String formatDep(Path buckThirdPartyRelativePath, Artifact artifact) {
      return String.format(
          "//%s/%s:%s",
          MorePaths.pathWithUnixSeparators(buckThirdPartyRelativePath),
          getProjectName(artifact),
          artifact.getArtifactId());
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public SortedSet<String> getVisibility() {
      if (publicVisibility) {
        return ImmutableSortedSet.of(PUBLIC_VISIBILITY);
      } else {
        return visibilities;
      }
    }

    @Override
    public int compareTo(Prebuilt that) {
      if (this == that) {
        return 0;
      }

      return this.name.compareTo(that.name);
    }
  }
}
