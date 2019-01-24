# Copyright 2017 Facebook. All Rights Reserved.
#
#!/usr/bin/env thrift -java
#
# This is a simple represenation of a rule key used
# in binary logging so that other tools can consume
# rulekeys. These types mirror some internal buck
# representations, but they all affect the hash key
# in different ways.
# Whenever you change this file please run the following command to refresh the java source code:
# $ thrift --gen java:generated_annotations=suppress -out src-gen/ \
#   src/com/facebook/buck/log/thrift/rulekeys.thrift

namespace java com.facebook.buck.log.thrift.rulekeys

struct NullValue {}

/* A path to a file, and the hash of its contents */
struct HashedPath {
  1: string path;
  2: string hash;
}

/* A path to a file */
struct NonHashedPath {
  1: string path;
}

/* An arbitrary Byte array */
struct ByteArray {
  1: i64 length;
}

/* 
 * A sha that contributes to the rule key. This is not
 * the sha of another rule key, that's the RuleKeyHash object
 * below. They can contribute to the hash differently
 */
struct Sha1 {
  1: string sha1;
}

/*
 * The hash of a rule key. This is the .key attribute
 * of another rule key
 */
struct RuleKeyHash {
  1: string sha1;
}

/*
 * A regular expression (e.g. \\w+)
 */
struct Pattern {
  1: string pattern;
}

/*
 * The path to an archive, the path within the archive,
 * and the hash of the file
 */
struct ArchiveMemberPath {
  1: string archivePath;
  2: string memberPath;
  3: string hash;
}

/*
 * A string describing the type of a rule. e.g. java_library
 */
struct BuildRuleType {
  1: string type;
}

/*
 * Used internally by buck for a number of reasons. The `type`
 * is something like OPTIONAL, or APPENDABLE. It contains one
 * nested value. The hash key can change depending on what type
 * of container is presented
 */
struct Wrapper {
  1: string type;
  2: Value value;
}

/*
 * A string repesentation of a target
 */
struct BuildTarget {
  1: string name;
}

/*
 * The path to the output of another rule
 */
struct TargetPath {
  1: string path;
}

/*
 * A "key" type used internally by buck for some key value pairs
 * This is generally converted to a string either in the values
 * field of FullRuleKey, or in a containerMap in a Value
 */
struct Key {
  1: string key;
}

/*
 * A combination of all of the types of objects that can contribute
 * (potentially differently) to a rule key
 */
union Value {
  1: string stringValue;
  2: double numberValue;
  3: bool boolValue;
  4: NullValue nullValue;
  5: HashedPath hashedPath;
  6: NonHashedPath path;
  7: Sha1 sha1Hash;
  8: Pattern pattern;
  9: ByteArray byteArray;
  10: map<string, Value> containerMap;
  11: list<Value> containerList;
  12: RuleKeyHash ruleKeyHash;
  13: ArchiveMemberPath archiveMemberPath;
  // 14: DEPRECATED
  15: BuildRuleType buildRuleType;
  16: Wrapper wrapper;
  17: BuildTarget buildTarget;
  18: TargetPath targetPath;
  19: Key key;
}

/*
 * A representation of a rule key with some common fields pre
 * populated (such as the key's hash, target name if available,
 * and type if available), along with a map of all of the
 * properties that contributed to the rule key's hash
 */
struct FullRuleKey {
  1: string key;
  2: string name;
  3: string type;
  4: map<string, Value> values;
}
