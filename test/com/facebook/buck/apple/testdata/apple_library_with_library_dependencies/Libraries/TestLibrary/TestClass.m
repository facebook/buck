#import "TestClass.h"

#import <TestLibraryDep/TestClassDep.h>

#import <string.h>

@implementation TestClass

+ (int)answer {
  return strlen([TestClassDep question]) - 3;
}

@end
