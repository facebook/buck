# Copyright 2016 Facebook. All Rights Reserved.
#
# To refresh the protocol run the following command:
#   /usr/local/bin/thrift --gen java -out src-gen/ src/com/facebook/buck/artifact_cache/thrift/buckcache.thrift
#
# This .thrift file contains the protocol required by the buck client to
# communicate with the buck-cache server.
# This protocol is under active development and
# will likely be changed in non-compatible ways

namespace java com.facebook.buck.artifact_cache.thrift

enum BuckCacheRequestType {
  UNKNOWN = 0,
  FETCH = 100,
  STORE = 101,
}

struct RuleKey {
  1: optional string hashString;
}

struct ArtifactMetadata {
  1: optional list<RuleKey> ruleKeys;
  2: optional map<string, string> metadata;
  3: optional string buildTarget;
  4: optional string repository;
  5: optional string artifactPayloadCrc32;
  6: optional string scheduleType;
}

struct BuckCacheStoreRequest {
  1: optional ArtifactMetadata metadata;

  // If this field is not present then the payload is passed via a different
  // out of band method.
  100: optional binary payload;
}

struct BuckCacheStoreResponse {
}

struct BuckCacheFetchRequest {
  1: optional RuleKey ruleKey;
  2: optional string repository;
}

struct BuckCacheFetchResponse {
  1: optional bool artifactExists;
  2: optional ArtifactMetadata metadata;

  // If this field is not present then the payload is passed via a different
  // out of band method.
  100: optional binary payload;
}

struct PayloadInfo {
  1: optional i64 sizeBytes;
}

struct BuckCacheRequest {
  1: optional BuckCacheRequestType type = BuckCacheRequestType.UNKNOWN;

  100: optional list<PayloadInfo> payloads;
  101: optional BuckCacheFetchRequest fetchRequest;
  102: optional BuckCacheStoreRequest storeRequest;
}

struct BuckCacheResponse {
  1: optional bool wasSuccessful;
  2: optional string errorMessage;

  10: optional BuckCacheRequestType type = BuckCacheRequestType.UNKNOWN;

  100: optional list<PayloadInfo> payloads;
  101: optional BuckCacheFetchResponse fetchResponse;
  102: optional BuckCacheStoreResponse storeResponse;
}
