#import "HUDTelemetry.h"

@interface HUDView : UIView
@property(nonatomic) HUDTelemetry *telemetry;
@property(nonatomic) BOOL portrait, attitudeEnabled;
@property(nonatomic, copy) NSString *status;
@end
