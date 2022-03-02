#import <ObjectiveCTestDependency/ObjectiveCTestDependency-Swift.h>

// Symbols are not automatically exported from ObjectiveC when modules are
// enabled, so we need to manually import ObjectiveC in addition to the test
// dependency. We still need to add the imports of the objc/ headers for non
// modules compilation, as otherwise we would be unable to parse the declaration
// of a class that uses symbols from the runtime APIs, as Test uses Method.
#if __has_feature(modules)
@import ObjectiveC;
#endif

static id test(Method method) {
    return [[Test alloc] init:method];
}
