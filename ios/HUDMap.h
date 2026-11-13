#import <UIKit/UIKit.h>
#import <CoreLocation/CoreLocation.h>

@interface HUDMap : UIView
@property(nonatomic, getter=isMapEnabled) BOOL mapEnabled;
@property(nonatomic) BOOL portrait;
- (void)showFix:(CLLocation *)fix course:(double)course message:(NSString *)message;
@end
