// Copyright 2016 Facebook, Inc.
#include <lib/lib.h>

#include <iostream>
#include <functional>
#include <unordered_map>

#include <gtest/gtest.h>

TEST_F(Basic) {
  EXPECT_EQ(0, sum(1, -1));
}
