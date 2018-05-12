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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.resolver.impl.TestBuildRuleResolver;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.MavenPublishable;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.exceptions.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Paths;
import java.util.Optional;
import org.eclipse.aether.deployment.DeploymentException;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class PublisherTest {

  private static final String MVN_COORDS_A = "com.facebook.buck.maven:a:jar:42";
  private static final String MVN_COORDS_B = "com.facebook.buck.maven:b:jar:10";

  @Rule public ExpectedException expectedException = ExpectedException.none();

  private Publisher publisher;

  @Before
  public void setUp() {
    ProjectFilesystem filesystem = new FakeProjectFilesystem();
    publisher =
        new Publisher(
            filesystem.getRootPath(),
            /* remoteRepoUrl */ Publisher.MAVEN_CENTRAL,
            /* username */ Optional.empty(),
            /* password */ Optional.empty(),
            /* dryRun */ true);
  }

  @Test
  public void errorRaisedForDuplicateFirstOrderDeps()
      throws DeploymentException, NoSuchBuildTargetException {
    // Construct a graph that looks like this.  A and B have maven coordinates set.
    // A   B
    //  \ /
    //   C
    BuildTarget publishableTargetA =
        BuildTargetFactory.newInstance("//:a").withFlavors(JavaLibrary.MAVEN_JAR);
    BuildTarget publishableTargetB =
        BuildTargetFactory.newInstance("//:b").withFlavors(JavaLibrary.MAVEN_JAR);
    BuildTarget targetC = BuildTargetFactory.newInstance("//:c");

    TargetNode<?, ?> depNode =
        JavaLibraryBuilder.createBuilder(targetC).addSrc(Paths.get("c.java")).build();
    TargetNode<?, ?> publishableANode =
        JavaLibraryBuilder.createBuilder(publishableTargetA)
            .addSrc(Paths.get("a.java"))
            .setMavenCoords(MVN_COORDS_A)
            .addDep(targetC)
            .build();
    TargetNode<?, ?> publishableBNode =
        JavaLibraryBuilder.createBuilder(publishableTargetB)
            .addSrc(Paths.get("b.java"))
            .setMavenCoords(MVN_COORDS_B)
            .addDep(targetC)
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(depNode, publishableANode, publishableBNode);
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));

    MavenPublishable publishableA = (MavenPublishable) resolver.requireRule(publishableTargetA);
    MavenPublishable publishableB = (MavenPublishable) resolver.requireRule(publishableTargetB);

    expectedException.expect(DeploymentException.class);
    expectedException.expectMessage(
        Matchers.containsString(targetC.getUnflavoredBuildTarget().getFullyQualifiedName()));
    expectedException.expectMessage(
        Matchers.containsString(
            publishableTargetA.getUnflavoredBuildTarget().getFullyQualifiedName()));
    expectedException.expectMessage(
        Matchers.containsString(
            publishableTargetB.getUnflavoredBuildTarget().getFullyQualifiedName()));

    publisher.publish(pathResolver, ImmutableSet.of(publishableA, publishableB));
  }

  @Test
  public void errorRaisedForDuplicateFirstOrderAndTransitiveDep()
      throws DeploymentException, NoSuchBuildTargetException {
    // Construct a graph that looks like this.  A and B have maven coordinates set.
    // A   B
    // |   |
    // C   |
    //  \ /
    //   D
    BuildTarget publishableTargetA =
        BuildTargetFactory.newInstance("//:a").withFlavors(JavaLibrary.MAVEN_JAR);
    BuildTarget publishableTargetB =
        BuildTargetFactory.newInstance("//:b").withFlavors(JavaLibrary.MAVEN_JAR);
    BuildTarget targetC = BuildTargetFactory.newInstance("//:c");
    BuildTarget targetD = BuildTargetFactory.newInstance("//:d");

    TargetNode<?, ?> transitiveDepNode =
        JavaLibraryBuilder.createBuilder(targetD).addSrc(Paths.get("d.java")).build();
    TargetNode<?, ?> depNode =
        JavaLibraryBuilder.createBuilder(targetC)
            .addSrc(Paths.get("c.java"))
            .addDep(targetD)
            .build();
    TargetNode<?, ?> publishableANode =
        JavaLibraryBuilder.createBuilder(publishableTargetA)
            .addSrc(Paths.get("a.java"))
            .setMavenCoords(MVN_COORDS_A)
            .addDep(targetC)
            .build();
    TargetNode<?, ?> publishableBNode =
        JavaLibraryBuilder.createBuilder(publishableTargetB)
            .addSrc(Paths.get("b.java"))
            .setMavenCoords(MVN_COORDS_B)
            .addDep(targetD)
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            transitiveDepNode, depNode, publishableANode, publishableBNode);
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));

    MavenPublishable publishableA = (MavenPublishable) resolver.requireRule(publishableTargetA);
    MavenPublishable publishableB = (MavenPublishable) resolver.requireRule(publishableTargetB);

    expectedException.expect(DeploymentException.class);
    expectedException.expectMessage(
        Matchers.containsString(targetD.getUnflavoredBuildTarget().getFullyQualifiedName()));
    expectedException.expectMessage(
        Matchers.containsString(
            publishableTargetA.getUnflavoredBuildTarget().getFullyQualifiedName()));
    expectedException.expectMessage(
        Matchers.containsString(
            publishableTargetB.getUnflavoredBuildTarget().getFullyQualifiedName()));

    publisher.publish(pathResolver, ImmutableSet.of(publishableA, publishableB));
  }

  @Test
  public void errorRaisedForDuplicateTransitiveDeps()
      throws DeploymentException, NoSuchBuildTargetException {
    // Construct a graph that looks like this.  A and B have maven coordinates set.
    // A   B
    // |   |
    // C   D
    //  \ /
    //   E
    BuildTarget publishableTargetA =
        BuildTargetFactory.newInstance("//:a").withFlavors(JavaLibrary.MAVEN_JAR);
    BuildTarget publishableTargetB =
        BuildTargetFactory.newInstance("//:b").withFlavors(JavaLibrary.MAVEN_JAR);
    BuildTarget targetC = BuildTargetFactory.newInstance("//:c");
    BuildTarget targetD = BuildTargetFactory.newInstance("//:d");
    BuildTarget targetE = BuildTargetFactory.newInstance("//:e");

    TargetNode<?, ?> transitiveDepNode =
        JavaLibraryBuilder.createBuilder(targetE).addSrc(Paths.get("e.java")).build();
    TargetNode<?, ?> dep1Node =
        JavaLibraryBuilder.createBuilder(targetC)
            .addSrc(Paths.get("c.java"))
            .addDep(targetE)
            .build();
    TargetNode<?, ?> dep2Node =
        JavaLibraryBuilder.createBuilder(targetD)
            .addSrc(Paths.get("d.java"))
            .addDep(targetE)
            .build();
    TargetNode<?, ?> publishableANode =
        JavaLibraryBuilder.createBuilder(publishableTargetA)
            .addSrc(Paths.get("a.java"))
            .setMavenCoords(MVN_COORDS_A)
            .addDep(targetC)
            .build();
    TargetNode<?, ?> publishableBNode =
        JavaLibraryBuilder.createBuilder(publishableTargetB)
            .addSrc(Paths.get("b.java"))
            .setMavenCoords(MVN_COORDS_B)
            .addDep(targetD)
            .build();

    TargetGraph targetGraph =
        TargetGraphFactory.newInstance(
            transitiveDepNode, dep1Node, dep2Node, publishableANode, publishableBNode);
    BuildRuleResolver resolver = new TestBuildRuleResolver(targetGraph);
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(resolver));

    MavenPublishable publishableA = (MavenPublishable) resolver.requireRule(publishableTargetA);
    MavenPublishable publishableB = (MavenPublishable) resolver.requireRule(publishableTargetB);

    expectedException.expect(DeploymentException.class);
    expectedException.expectMessage(
        Matchers.containsString(targetE.getUnflavoredBuildTarget().getFullyQualifiedName()));
    expectedException.expectMessage(
        Matchers.containsString(
            publishableTargetA.getUnflavoredBuildTarget().getFullyQualifiedName()));
    expectedException.expectMessage(
        Matchers.containsString(
            publishableTargetB.getUnflavoredBuildTarget().getFullyQualifiedName()));

    publisher.publish(pathResolver, ImmutableSet.of(publishableA, publishableB));
  }
}
