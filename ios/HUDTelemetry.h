#import <UIKit/UIKit.h>
#import <CoreLocation/CoreLocation.h>
#import <CoreMotion/CoreMotion.h>

@interface HUDTelemetry : NSObject <CLLocationManagerDelegate>
@property(nonatomic, readonly) CLLocationManager *locationManager;
@property(nonatomic, readonly) CLLocation *fix;
@property(nonatomic, readonly) BOOL ready;
@property(nonatomic, readonly) BOOL started;
@property(nonatomic, readonly) BOOL preciseLocation;
@property(nonatomic, readonly) BOOL courseAvailable;
@property(nonatomic, readonly) BOOL usingBarometer;
@property(nonatomic, readonly) double speed, altitude, heading, pitch, roll, acceleration;
@property(nonatomic, readonly) double lateralG, mapCourse;
@property(nonatomic, readonly) double peakSpeed, peakAltitude, peakPitch, peakLeftG, peakRightG;
@property(nonatomic, readonly) double minimumAltitude, minimumPitch;
- (void)resume;
- (void)pause;
- (BOOL)calibrate:(UIInterfaceOrientation)orientation;
- (void)suspendAttitude;
- (void)resetPeaks;
- (void)requestLocation;
@end
