#import "DemoMix-Swift.h"

int main(int argc, const char * argv[]) {
    if ([[Greeter class] respondsToSelector:@selector(sayHello:)]) {
        // Swift Version < 3
        [Greeter sayHello:@"Swift"];
    } else {
        // Swift Version >= 3
        [Greeter sayHelloWithName:@"Swift"];
    }
    return 0;
}
