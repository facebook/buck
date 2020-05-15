#import <Foundation/Foundation.h>

static inline void logAnalytics(NSString *analytics) {
  NSLog(@"Analytics: %@", analytics);
}

@interface Analytics : NSObject

@end
