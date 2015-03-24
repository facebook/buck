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
import static org.eclipse.aether.repository.RepositoryPolicy.CHECKSUM_POLICY_FAIL;
import static org.eclipse.aether.util.artifact.JavaScopes.TEST;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.Resources;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryException;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.impl.DefaultServiceLocator;
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
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.spi.locator.ServiceLocator;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.artifact.SubArtifact;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroupString;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

public class Resolver {

  private static final String TEMPLATE =
      Resolver.class.getPackage().getName().replace(".", "/") + "/build-file.st";

  private final Path buckRepoRoot;
  private final Path buckThirdPartyRelativePath;
  private final LocalRepository localRepo;
  private final ImmutableList<RemoteRepository> repos;
  private final ServiceLocator locator;

  public Resolver(
      Path buckRepoRoot,
      Path relativeThirdParty,
      Path localRepoPath,
      String... repoUrls) {
    this.buckRepoRoot = buckRepoRoot;
    this.buckThirdPartyRelativePath = relativeThirdParty;
    this.localRepo = new LocalRepository(localRepoPath.toFile());

    ImmutableList.Builder<RemoteRepository> builder = ImmutableList.builder();
    for (int i = 0; i < repoUrls.length; i++) {
      RemoteRepository.Builder remote =
          new RemoteRepository.Builder("remote " + i, "default", repoUrls[i])
              .setPolicy(new RepositoryPolicy(true, null, CHECKSUM_POLICY_FAIL));
      builder.add(remote.build());
    }
    this.repos = builder.build();

    DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
    locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
    locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
    this.locator = locator;
  }

  public void resolve(String... mavenCoords) throws RepositoryException, IOException {
    RepositorySystem repoSys = locator.getService(RepositorySystem.class);
    RepositorySystemSession session = newSession(repoSys);

    ImmutableMap<String, Artifact> knownDeps = getRunTimeTransitiveDeps(
        repoSys,
        session,
        mavenCoords);

    // We now have the complete set of dependencies. Build the graph of dependencies. We'd like
    // aether to do this for us, but it doesn't preserve the complete dependency information we need
    // to accurately construct build files.
    MutableDirectedGraph<Artifact> graph = buildDependencyGraph(repoSys, session, knownDeps);

    // Now we have the graph, grab the sources and jars for each dependency, as well as the relevant
    // checksums (which are download by default. Yay!)

    Map<Path, SortedSet<Prebuilt>> buckFiles = new HashMap<>();

    for (Artifact root : graph.getNodes()) {
      int index = root.getGroupId().lastIndexOf('.');
      String projectName = root.getGroupId();
      if (index != -1) {
        projectName = projectName.substring(index + 1);
      }
      Path project = buckRepoRoot.resolve(buckThirdPartyRelativePath).resolve(projectName);
      Files.createDirectories(project);

      SortedSet<Prebuilt> libs = buckFiles.get(project);
      if (libs == null) {
        libs = new TreeSet<>();
        buckFiles.put(project, libs);
      }

      Artifact jar = new DefaultArtifact(
          root.getGroupId(),
          root.getArtifactId(),
          "jar",
          root.getVersion());
      Artifact srcs = new SubArtifact(jar, "sources", "jar");

      ArtifactResult result = repoSys.resolveArtifact(
          session,
          new ArtifactRequest(jar, repos, null));
      Path relativePath = copy(result, project);

      Prebuilt library = new Prebuilt(jar.getArtifactId(), relativePath);
      libs.add(library);

      try {
        result = repoSys.resolveArtifact(session, new ArtifactRequest(srcs, repos, null));
        relativePath = copy(result, project);
        library.setSourceJar(relativePath);
      } catch (ArtifactResolutionException e) {
        System.err.println("Skipping sources for: " + srcs);
      }

      Iterable<Artifact> incoming = graph.getIncomingNodesFor(root);
      for (Artifact artifact : incoming) {
        index = artifact.getGroupId().lastIndexOf('.');
        String groupName = artifact.getGroupId();
        if (index != -1) {
          groupName = groupName.substring(index + 1);
        }
        if (projectName.equals(groupName)) {
          library.addDep(String.format(":%s", artifact.getArtifactId()));
        } else {
          library.addDep(
              String.format(
                  "//%s/%s:%s",
                  buckThirdPartyRelativePath,
                  groupName,
                  artifact.getArtifactId()));
        }
      }

      Iterable<Artifact> outgoing = graph.getOutgoingNodesFor(root);
      for (Artifact artifact : outgoing) {
        index = artifact.getGroupId().lastIndexOf('.');
        String groupName = artifact.getGroupId();
        if (index != -1) {
          groupName = groupName.substring(index + 1);
        }
        if (!groupName.equals(projectName)) {
          library.addVisibility(
              String.format(
                  "//%s/%s:%s",
                  buckThirdPartyRelativePath,
                  groupName,
                  artifact.getArtifactId()));
        }
      }
    }

    URL templateUrl = Resources.getResource(TEMPLATE);
    String template = Resources.toString(templateUrl, UTF_8);
    STGroupString groups = new STGroupString("prebuilt-template", template);

    for (Map.Entry<Path, SortedSet<Prebuilt>> entry : buckFiles.entrySet()) {
      Path buckFile = entry.getKey().resolve("BUCK");
      if (Files.exists(buckFile)) {
        Files.delete(buckFile);
      }

      ST st = Preconditions.checkNotNull(groups.getInstanceOf("/prebuilts"));
      st.add("data", entry.getValue());
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

  private  MutableDirectedGraph<Artifact> buildDependencyGraph(
      RepositorySystem repoSys,
      RepositorySystemSession session,
      Map<String, Artifact> knownDeps) throws ArtifactDescriptorException {
    MutableDirectedGraph<Artifact> graph;
    graph = new MutableDirectedGraph<>();
    for (Artifact dep : knownDeps.values()) {
      String key = buildKey(dep);

      Preconditions.checkNotNull(knownDeps.get(key));

      graph.addNode(dep);

      List<Dependency> dependencies = getDependenciesOf(repoSys, session, dep);

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

        Preconditions.checkNotNull(actualDep, key + " -> " + dep + " in " + knownDeps.keySet());
        graph.addNode(actualDep);
        graph.addEdge(actualDep, dep);
      }
    }
    return graph;
  }

  private List<Dependency> getDependenciesOf(
      RepositorySystem repoSys,
      RepositorySystemSession session,
      Artifact dep) throws ArtifactDescriptorException {
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

  private ImmutableMap<String, Artifact> getRunTimeTransitiveDeps(
      RepositorySystem repoSys,
      RepositorySystemSession session,
      String... mavenCoords)
      throws RepositoryException {

    CollectRequest collectRequest = new CollectRequest();
    collectRequest.setRequestContext(JavaScopes.RUNTIME);
    collectRequest.setRepositories(repos);

    for (String coord : mavenCoords) {
      DefaultArtifact artifact = new DefaultArtifact(coord);
      collectRequest.addDependency(new Dependency(artifact, JavaScopes.RUNTIME));

      ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest();
      descriptorRequest.setArtifact(artifact);
      // Setting this appears to have exactly zero effect on the returned values. *sigh*
//      descriptorRequest.setRequestContext(JavaScopes.RUNTIME);
      descriptorRequest.setRepositories(repos);
      ArtifactDescriptorResult descriptorResult = repoSys.readArtifactDescriptor(
          session,
          descriptorRequest);

      for (Dependency dependency : descriptorResult.getDependencies()) {
        if (isTestTime(dependency)) {
          continue;
        }
        collectRequest.addDependency(dependency);
      }
      for (Dependency dependency : descriptorResult.getManagedDependencies()) {
        if (isTestTime(dependency)) {
          continue;
        }
        collectRequest.addManagedDependency(dependency);
      }
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

  private RepositorySystemSession newSession(RepositorySystem repoSys) {
    DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

    session.setLocalRepositoryManager(repoSys.newLocalRepositoryManager(session, localRepo));
    session.setReadOnly();

    return session;
  }

  /**
   * Construct a key to identify the artifact, less its version
   */
  private String buildKey(Artifact artifact) {
    return artifact.getGroupId() +
        ':' + artifact.getArtifactId() +
        ':' + artifact.getExtension() +
        ':' + artifact.getClassifier();
  }

  public static void main(String[] args) throws RepositoryException, IOException {
    if (args.length < 5) {
      System.err.println("Usage: java -jar resolver.jar buck-repo third-party " +
              "maven-local-repo maven-url junit:junit:jar:4.12...");
      System.exit(1);
    }

    Path buckRepoRoot = Paths.get(args[0]);
    Path thirdParty = Paths.get(args[1]);
    Path m2 = Paths.get(args[2]);
    String mavenCentral = args[3];
    String[] coords = Arrays.copyOfRange(args, 4, args.length);

    new Resolver(
        buckRepoRoot,
        thirdParty,
        m2,
        mavenCentral)
        .resolve(coords);
  }

  private static class Prebuilt implements Comparable<Prebuilt> {

    private final String name;
    private final Path binaryJar;
    private Path sourceJar;
    private final SortedSet<String> deps = new TreeSet<>(new BuckDepComparator());
    private final SortedSet<String> visibilities = new TreeSet<>(new BuckDepComparator());

    public Prebuilt(String name, Path binaryJar) {
      this.name = name;
      this.binaryJar = binaryJar;
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public String getName() {
      return name;
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public Path getBinaryJar() {
      return binaryJar;
    }

    public void setSourceJar(Path sourceJar) {
      this.sourceJar = sourceJar;
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public Path getSourceJar() {
      return sourceJar;
    }

    public void addDep(String dep) {
      this.deps.add(dep);
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public SortedSet<String> getDeps() {
      return deps;
    }

    public void addVisibility(String dep) {
      this.visibilities.add(dep);
    }

    @SuppressWarnings("unused") // This method is read reflectively.
    public SortedSet<String> getVisibility() {
      return visibilities;
    }

    @Override
    public int compareTo(Prebuilt that) {
      return this.name.compareTo(that.name);
    }
  }
}
