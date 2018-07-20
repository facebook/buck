#import <HelloProxy/HelloProxy.h>

int main(int argc, char *argv[])
{
    @autoreleasepool
    {
        NSLog(@"%@", [[HelloProxy new] sayHelloFancily]);
        return 0;
    }
}
