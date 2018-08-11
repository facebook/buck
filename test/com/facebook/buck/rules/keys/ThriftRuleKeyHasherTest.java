/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.model.impl.ImmutableBuildTarget;
import com.facebook.buck.core.model.impl.ImmutableUnflavoredBuildTarget;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.sourcepath.AbstractDefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.thrift.ThriftRuleKeyLogger;
import com.facebook.buck.log.thrift.rulekeys.ByteArray;
import com.facebook.buck.log.thrift.rulekeys.FullRuleKey;
import com.facebook.buck.log.thrift.rulekeys.HashedPath;
import com.facebook.buck.log.thrift.rulekeys.NonHashedPath;
import com.facebook.buck.log.thrift.rulekeys.NullValue;
import com.facebook.buck.log.thrift.rulekeys.RuleKeyHash;
import com.facebook.buck.log.thrift.rulekeys.Sha1;
import com.facebook.buck.log.thrift.rulekeys.TargetPath;
import com.facebook.buck.log.thrift.rulekeys.Value;
import com.facebook.buck.log.thrift.rulekeys.Wrapper;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.rules.keys.hasher.ThriftRuleKeyHasher;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TCompactProtocol;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ThriftRuleKeyHasherTest {
  ByteArrayOutputStream output;
  ThriftRuleKeyLogger logger;
  ThriftRuleKeyHasher hasher;

  @Before
  public void setUp() {
    output = new ByteArrayOutputStream();
    logger = new ThriftRuleKeyLogger(output);
    hasher = new ThriftRuleKeyHasher(logger);
  }

  @After
  public void tearDown() {
    logger.close();
  }

  private FullRuleKey getRuleKey() throws TException {
    byte[] outputArray = output.toByteArray();
    ByteBuffer lengthBuf = ByteBuffer.wrap(outputArray, 0, 4);
    int length = lengthBuf.getInt();

    Assert.assertEquals(outputArray.length - 4, length);

    TDeserializer serializer = new TDeserializer(new TCompactProtocol.Factory());
    FullRuleKey ruleKey = new FullRuleKey();
    serializer.deserialize(ruleKey, Arrays.copyOfRange(outputArray, 4, outputArray.length));
    return ruleKey;
  }

  @Test
  public void testPopulatesNameAndType() throws TException {
    hasher.putString("-DCONFIG");
    hasher.putKey("some_key");
    hasher.putString("rule_name");
    hasher.putKey(".target_name");
    hasher.putString("other_rule_name");
    hasher.putKey(".name");
    hasher.putString("rule_type");
    hasher.putKey(".rule_key_type");
    hasher.putString("other_rule_type");
    hasher.putKey(".type");
    hasher.setHashKey(HashCode.fromString("0fb14bd529a66e7f6299d9853f9e5f178c6e3866"));
    hasher.hash();
    hasher.flushToLogger();

    FullRuleKey ruleKey = getRuleKey();

    Assert.assertEquals("0fb14bd529a66e7f6299d9853f9e5f178c6e3866", ruleKey.key);
    Assert.assertEquals("rule_name", ruleKey.name);
    Assert.assertEquals("rule_type", ruleKey.type);
    Assert.assertEquals("-DCONFIG", ruleKey.values.get("some_key").getStringValue());
  }

  @Test
  public void testPopulatesNameAndTypeWithAlternateName() throws TException {
    hasher.putString("-DCONFIG");
    hasher.putKey("some_key");
    hasher.putString("other_rule_name");
    hasher.putKey(".name");
    hasher.putString("other_rule_type");
    hasher.putKey(".type");
    hasher.setHashKey(HashCode.fromString("0fb14bd529a66e7f6299d9853f9e5f178c6e3866"));
    hasher.hash();
    hasher.flushToLogger();
    logger.close();

    FullRuleKey ruleKey = getRuleKey();

    Assert.assertEquals("0fb14bd529a66e7f6299d9853f9e5f178c6e3866", ruleKey.key);
    Assert.assertEquals("other_rule_name", ruleKey.name);
    Assert.assertEquals("other_rule_type", ruleKey.type);
    Assert.assertEquals("-DCONFIG", ruleKey.values.get("some_key").getStringValue());
  }

  @Test
  public void handlesAllTypes() throws Exception {
    hasher.putNull();
    hasher.putKey(".null_value");
    hasher.putBoolean(true);
    hasher.putKey(".boolean_value");
    hasher.putNumber(1234);
    hasher.putKey(".number_value");
    hasher.putString("string");
    hasher.putKey(".string_value");
    hasher.putBytes("test".getBytes("utf-8"));
    hasher.putKey(".bytes_value");
    hasher.putPattern(Pattern.compile("\\w+"));
    hasher.putKey(".pattern_value");
    hasher.putSha1(Sha1HashCode.of("e076ff7a11cff31a5c11102bdeaaf571d90f6479"));
    hasher.putKey(".sha1_value");
    hasher.putPath(
        new File("test").toPath(), HashCode.fromString("0503e9786d39160e3811ea609c512530c7f66e82"));
    hasher.putKey(".path_value");
    hasher.putArchiveMemberPath(
        ArchiveMemberPath.of(new File("archive_path").toPath(), new File("member_path").toPath()),
        HashCode.fromString("f9d8ff855a16a3a3d28bfb445cc440502d6e895a"));
    hasher.putKey(".archive_member_path_value");
    hasher.putNonHashingPath("non_hashing_test");
    hasher.putKey(".non_hashing_path_value");
    hasher.putSourceRoot(new SourceRoot("source_root"));
    hasher.putKey(".source_root_value");
    hasher.putRuleKey(new RuleKey(HashCode.fromString("d0c852385a66458b6e960c89fac580e5eb6d6aec")));
    hasher.putKey(".rule_key_value");
    hasher.putRuleType(RuleType.of("sample_build_rule", RuleType.Kind.BUILD));
    hasher.putKey(".build_rule_type_value");
    hasher.putBuildTarget(
        ImmutableBuildTarget.of(
            ImmutableUnflavoredBuildTarget.of(
                new File("cell_path").toPath(), Optional.empty(), "//base_name", "rule_name")));
    hasher.putKey(".build_target_value");
    hasher.putBuildTargetSourcePath(
        new AbstractDefaultBuildTargetSourcePath() {
          @Override
          public BuildTarget getTarget() {
            return ImmutableBuildTarget.of(
                ImmutableUnflavoredBuildTarget.of(
                    new File("cell_path_2").toPath(),
                    Optional.empty(),
                    "//base_name_2",
                    "rule_name_2"));
          }
        });
    hasher.putKey(".build_target_source_path_value");

    hasher.putString("string3");
    hasher.putString("string2");
    hasher.putWrapper(RuleKeyHasher.Wrapper.OPTIONAL);
    hasher.putString("string1");
    hasher.putContainer(RuleKeyHasher.Container.LIST, 3);
    hasher.putKey(".list_value");

    hasher.putNumber(2);
    hasher.putKey("kv_tuple_key_2");
    hasher.putString("string6");
    hasher.putString("string5");
    hasher.putContainer(RuleKeyHasher.Container.LIST, 2);
    hasher.putKey("kv_tuple_key");
    hasher.putContainer(RuleKeyHasher.Container.TUPLE, 2);
    hasher.putKey(".kv_tuple_value");

    hasher.putString("string8");
    hasher.putString("string7");
    hasher.putContainer(RuleKeyHasher.Container.TUPLE, 2);
    hasher.putKey(".list_tuple_value");

    hasher.putString("map_value_key2");
    hasher.putString("string12");
    hasher.putString("string11");
    hasher.putContainer(RuleKeyHasher.Container.LIST, 2);
    hasher.putString("map_value_key1");
    hasher.putString("string10");
    hasher.putString("string9");
    hasher.putContainer(RuleKeyHasher.Container.TUPLE, 2);
    hasher.putContainer(RuleKeyHasher.Container.MAP, 4); // Maps count keys + values
    hasher.putKey(".map_value");

    hasher.putString("wrapper_string");
    hasher.putWrapper(RuleKeyHasher.Wrapper.OPTIONAL);
    hasher.putKey(".wrapper_value");

    hasher.putString("rule_name");
    hasher.putKey(".target_name");
    hasher.putString("rule_type");
    hasher.putKey(".rule_key_type");
    hasher.setHashKey(HashCode.fromString("0fb14bd529a66e7f6299d9853f9e5f178c6e3866"));
    hasher.hash();
    hasher.flushToLogger();

    Map<String, Value> expectedValues =
        ImmutableMap.<String, Value>builder()
            .put(".rule_key_type", Value.stringValue("rule_type"))
            .put(".target_name", Value.stringValue("rule_name"))
            .put(".null_value", Value.nullValue(new NullValue()))
            .put(".boolean_value", Value.boolValue(true))
            .put(".number_value", Value.numberValue(1234))
            .put(".string_value", Value.stringValue("string"))
            .put(".bytes_value", Value.byteArray(new ByteArray(4)))
            .put(
                ".pattern_value",
                Value.pattern(new com.facebook.buck.log.thrift.rulekeys.Pattern("\\w+")))
            .put(
                ".sha1_value", Value.sha1Hash(new Sha1("e076ff7a11cff31a5c11102bdeaaf571d90f6479")))
            .put(
                ".path_value",
                Value.hashedPath(
                    new HashedPath("test", "0503e9786d39160e3811ea609c512530c7f66e82")))
            .put(
                ".archive_member_path_value",
                Value.archiveMemberPath(
                    new com.facebook.buck.log.thrift.rulekeys.ArchiveMemberPath(
                        "archive_path", "member_path", "f9d8ff855a16a3a3d28bfb445cc440502d6e895a")))
            .put(".non_hashing_path_value", Value.path(new NonHashedPath("non_hashing_test")))
            .put(
                ".source_root_value",
                Value.sourceRoot(
                    new com.facebook.buck.log.thrift.rulekeys.SourceRoot("source_root")))
            .put(
                ".rule_key_value",
                Value.ruleKeyHash(new RuleKeyHash("d0c852385a66458b6e960c89fac580e5eb6d6aec")))
            .put(
                ".build_rule_type_value",
                Value.buildRuleType(
                    new com.facebook.buck.log.thrift.rulekeys.BuildRuleType("sample_build_rule")))
            .put(
                ".build_target_value",
                Value.buildTarget(
                    new com.facebook.buck.log.thrift.rulekeys.BuildTarget("//base_name:rule_name")))
            .put(
                ".build_target_source_path_value",
                Value.targetPath(new TargetPath("//base_name_2:rule_name_2")))
            .put(
                ".list_value",
                Value.containerList(
                    ImmutableList.of(
                        Value.stringValue("string1"),
                        Value.wrapper(new Wrapper("OPTIONAL", Value.stringValue("string2"))),
                        Value.stringValue("string3"))))
            .put(
                ".kv_tuple_value",
                Value.containerMap(
                    ImmutableMap.of(
                        "kv_tuple_key",
                            Value.containerList(
                                ImmutableList.of(
                                    Value.stringValue("string5"), Value.stringValue("string6"))),
                        "kv_tuple_key_2", Value.numberValue(2))))
            .put(
                ".list_tuple_value",
                Value.containerList(
                    ImmutableList.of(Value.stringValue("string7"), Value.stringValue("string8"))))
            .put(
                ".map_value",
                Value.containerMap(
                    ImmutableMap.of(
                        "map_value_key1",
                        Value.containerList(
                            ImmutableList.of(
                                Value.stringValue("string9"), Value.stringValue("string10"))),
                        "map_value_key2",
                        Value.containerList(
                            ImmutableList.of(
                                Value.stringValue("string11"), Value.stringValue("string12"))))))
            .put(
                ".wrapper_value",
                Value.wrapper(new Wrapper("OPTIONAL", Value.stringValue("wrapper_string"))))
            .build();

    FullRuleKey expected =
        new FullRuleKey(
            "0fb14bd529a66e7f6299d9853f9e5f178c6e3866", "rule_name", "rule_type", expectedValues);

    FullRuleKey ruleKey = getRuleKey();

    Assert.assertEquals(expected, ruleKey);
  }

  @Test
  public void canHandleForwardingBuildTargetSourcePathsWithDifferentFilesystems()
      throws TException {
    ProjectFilesystem filesystem1 = new FakeProjectFilesystem(Paths.get("first", "root"));
    ProjectFilesystem filesystem2 = new FakeProjectFilesystem(Paths.get("other", "root"));
    Path relativePath = Paths.get("arbitrary", "path");
    BuildTarget target = BuildTargetFactory.newInstance("//:target");

    ForwardingBuildTargetSourcePath forwardingSourcePath1 =
        ForwardingBuildTargetSourcePath.of(target, PathSourcePath.of(filesystem1, relativePath));
    ForwardingBuildTargetSourcePath forwardingSourcePath2 =
        ForwardingBuildTargetSourcePath.of(target, PathSourcePath.of(filesystem2, relativePath));

    hasher.putBuildTargetSourcePath(forwardingSourcePath1).putKey(".path1");
    hasher.putBuildTargetSourcePath(forwardingSourcePath2).putKey(".path2");

    hasher.setHashKey(HashCode.fromString("0fb14bd529a66e7f6299d9853f9e5f178c6e3866"));
    hasher.hash();
    hasher.flushToLogger();
    logger.close();

    FullRuleKey ruleKey = getRuleKey();

    Assert.assertEquals(ruleKey.values.get(".path1"), ruleKey.values.get(".path2"));
  }
}
