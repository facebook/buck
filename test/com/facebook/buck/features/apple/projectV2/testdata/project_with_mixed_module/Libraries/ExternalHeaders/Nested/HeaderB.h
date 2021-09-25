// Copyright 2004-present Facebook. All Rights Reserved.

#pragma once

#include <Foundation/Foundation.h>

struct HeaderB {
  int32_t TEST1;  // test add event
  int32_t TEST2;  // test add event
};

#ifdef __cplusplus
extern "C" {
#endif
extern const struct HeaderB HeaderBB;
#ifdef __cplusplus
}
#endif

