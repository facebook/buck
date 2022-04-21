// Copyright (c) Meta Platforms, Inc. and affiliates. All Rights Reserved.

#pragma once

#include <Foundation/Foundation.h>

struct HeaderA {
  int32_t TEST1;  // test add event
  int32_t TEST2;  // test add event
};

#ifdef __cplusplus
extern "C" {
#endif
extern const struct HeaderA HeaderAA;
#ifdef __cplusplus
}
#endif
