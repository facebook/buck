#import "TestClassDep.h"

#import <TestLibraryTransitiveDep/TestClassTransitiveDep.h>

@implementation TestClassDep

+ (char *)question {
  if (zero() == 0) {
    return "What do you get if you multiply six by nine?";
  }
  return "How are you?";
}

@end
