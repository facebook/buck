/*
 * Copyright 2018-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

#import <CapitalizeUtil.h>

NSString *CapitalizeFirstLetter(NSString *input) {
  NSString *firstLetter = [input substringToIndex:1];
  NSString *capitalLetter = [firstLetter uppercaseString];
  NSString *restOfString = [input substringFromIndex:1];
  return [capitalLetter stringByAppendingString:restOfString];
}