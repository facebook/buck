/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

namespace java com.facebook.buck.frontend.thrift

##############################################################################
## DataTypes
##############################################################################


struct ScribeData {
  1: optional string category;
  2: optional list<string> lines;
  3: optional i32 bucket;
}

enum LogRequestType {
  UNKNOWN = 0,
  SCRIBE_DATA = 1,
}

struct Announcement {
  1: optional string errorMessage;
  2: optional string solutionMessage;
}

##############################################################################
## Request/Response structs
##############################################################################

struct LogRequest {
  1: optional LogRequestType type = LogRequestType.UNKNOWN;
  2: optional ScribeData scribeData;
}

# Used to obtain announcements for users regarding current issues with Buck and
# solutions.
struct AnnouncementRequest {
  1: optional string buckVersion;
  2: optional string repository;
}

struct AnnouncementResponse {
  1: optional list<Announcement> announcements;
}

# Contains details about when a cache artifact was stored/fetched to a
# particular backing store.
struct RuleKeyStoreLogEntry {
  1: optional string storeId;
  2: optional i64 storeTTLSeconds;
  3: optional i64 lastStoreEpochSeconds;
  4: optional i64 lastAttemptedStoreEpochSeconds;
  5: optional i64 lastFetchEpochSeconds;
  6: optional string repository;
  7: optional string scheduleType;
  8: optional i64 fetchCount;
  9: optional i64 attemptedStoreCount;
}

struct RuleKeyLogEntry {
  1: optional string ruleKey;

  2: optional bool wasStored; // Deprecated
  3: optional i64 lastStoredTimestampMillis; // Deprecated

  4: optional list<RuleKeyStoreLogEntry> storeLogEntries;
}

struct FetchRuleKeyLogsRequest {
  1: optional list<string> ruleKeys;

  2: optional string repository;
  3: optional string scheduleType;
  // 4: DEPRECATED.
}

struct FetchRuleKeyLogsResponse {
  1: optional list<RuleKeyLogEntry> ruleKeyLogs;
  2: optional list<string> lookedUpStoreIds;
}

##############################################################################
## Top-Level Buck-Frontend HTTP body thrift Request/Response format
##############################################################################

enum FrontendRequestType {
  UNKNOWN = 0,
  // [1-4] Legacy request types. Do not use.
  LOG = 5,
  // [6-12] Legacy request types. Do not use.
  ANNOUNCEMENT = 13,
  // [14-21] Legacy request types. Do not use.
  FETCH_RULE_KEY_LOGS = 22,
  // [23-32] Legacy request types. Do not use.

  // [100-199] Values are reserved for the buck cache request types.
}

struct FrontendRequest {
  1: optional FrontendRequestType type = FrontendRequestType.UNKNOWN;
  // [2-5] Legacy request fields. Do not use.
  6: optional LogRequest logRequest;
  // [7-13] Legacy request fields. Do not use.
  14: optional AnnouncementRequest announcementRequest;
  // [15-21] Legacy request fields. Do not use.
  22: optional FetchRuleKeyLogsRequest fetchRuleKeyLogsRequest;
  // [23-29] Legacy request fields. Do not use.

  // [100-199] Values are reserved for the buck cache request types.
}

struct FrontendResponse {
  1: optional bool wasSuccessful;
  2: optional string errorMessage;
  // [3-9] Legacy fields. Do not use.
  10: optional FrontendRequestType type = FrontendRequestType.UNKNOWN;
  // [11-18] Legacy response fields. Do not use.
  19: optional AnnouncementResponse announcementResponse;
  // [20-25] Legacy response fields. Do not use.
  26: optional FetchRuleKeyLogsResponse fetchRuleKeyLogsResponse;
  // [27-33] Legacy response fields. Do not use.

  // [100-199] Values are reserved for the buck cache request types.
}
