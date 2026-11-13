#import "HUDTelemetry.h"
#import <simd/simd.h>

static const double FixTimeout = 6;
static const double Degrees = 180 / M_PI;

static double higherPeak(double peak, double value) {
    return isfinite(value) ? fmax(peak, value) : peak;
}

@implementation HUDTelemetry {
    CMMotionManager *_motion;
    CMAltimeter *_altimeter;
    CLLocationManager *_locationManager;
    CLLocation *_location;
    CLHeading *_magnetic;
    CMDeviceMotion *_attitude;
    simd_double3 _up, _referenceUp, _forward, _right;
    double _pitch, _roll, _acceleration, _lateralG;
    double _lastCourse;
    double _peakSpeed, _peakAltitude, _peakPitch, _peakLeftG, _peakRightG;
    double _minimumAltitude, _minimumPitch;
    double _relativeAltitude, _barometricAnchor, _mslAnchor;
    NSTimeInterval _barometerTime, _altitudeTime;
    BOOL _active, _started, _calibrated, _recordingPeaks;
}

@synthesize peakSpeed = _peakSpeed, peakAltitude = _peakAltitude, peakPitch = _peakPitch;
@synthesize peakLeftG = _peakLeftG, peakRightG = _peakRightG;
@synthesize minimumAltitude = _minimumAltitude, minimumPitch = _minimumPitch;

- (instancetype)init {
    if (!(self = [super init])) return nil;
    _motion = [CMMotionManager new];
    _motion.deviceMotionUpdateInterval = 1.0 / 40;
    _altimeter = [CMAltimeter new];
    _locationManager = [CLLocationManager new];
    _locationManager.delegate = self;
    _locationManager.desiredAccuracy = kCLLocationAccuracyBest;
    _locationManager.distanceFilter = kCLDistanceFilterNone;
    _locationManager.activityType = CLActivityTypeAutomotiveNavigation;
    _locationManager.pausesLocationUpdatesAutomatically = NO;
    _pitch = _roll = _acceleration = _lateralG = NAN;
    _lastCourse = NAN;
    _barometricAnchor = _mslAnchor = NAN;
    [self resetPeaks];
    return self;
}

- (CLLocationManager *)locationManager { return _locationManager; }
- (BOOL)started { return _started; }
- (BOOL)ready { return _attitude != nil; }
- (double)pitch { return _attitude ? _pitch : NAN; }
- (double)roll { return _attitude ? _roll : NAN; }
- (double)acceleration { return _attitude ? _acceleration : NAN; }
- (double)lateralG { return _attitude ? _lateralG : NAN; }

- (void)suspendAttitude { _calibrated = NO; }

- (void)resetPeaks {
    _recordingPeaks = NO;
    _peakSpeed = _peakAltitude = _peakPitch = -INFINITY;
    _minimumAltitude = INFINITY;
    _minimumPitch = 0;
    _peakLeftG = _peakRightG = 0;
}

- (void)resume {
    if (_active) return;
    _active = YES;
    __weak typeof(self) weakSelf = self;
    if (_motion.deviceMotionAvailable) {
        [_motion startDeviceMotionUpdatesUsingReferenceFrame:CMAttitudeReferenceFrameXArbitraryZVertical
                                                     toQueue:NSOperationQueue.mainQueue
                                                 withHandler:^(CMDeviceMotion *sample, NSError *error) {
            [weakSelf updateMotion:sample];
        }];
    }
    if (_started) [self startLocationAndBarometer];
}

- (void)pause {
    _active = NO;
    [_motion stopDeviceMotionUpdates];
    [_altimeter stopRelativeAltitudeUpdates];
    [_locationManager stopUpdatingLocation];
    [_locationManager stopUpdatingHeading];
    _location = nil;
    _magnetic = nil;
    _attitude = nil;
    _altitudeTime = _barometerTime = 0;
    _barometricAnchor = _mslAnchor = NAN;
    _acceleration = _lateralG = NAN;
}

- (BOOL)calibrate:(UIInterfaceOrientation)orientation {
    if (!self.ready) return NO;
    _referenceUp = _up;
    simd_double3 ahead = {0, 0, -1};
    if (fabs(_up.z) >= .85) {
        switch (orientation) {
            case UIInterfaceOrientationLandscapeLeft: ahead = (simd_double3){-1, 0, 0}; break;
            case UIInterfaceOrientationLandscapeRight: ahead = (simd_double3){1, 0, 0}; break;
            case UIInterfaceOrientationPortraitUpsideDown: ahead = (simd_double3){0, -1, 0}; break;
            default: ahead = (simd_double3){0, 1, 0}; break;
        }
    }
    _forward = simd_normalize(ahead - simd_dot(ahead, _up) * _up);
    _right = simd_cross(_forward, _up);
    _pitch = _roll = 0;
    _acceleration = _lateralG = NAN;
    _calibrated = _recordingPeaks = YES;
    _peakPitch = fmax(_peakPitch, _pitch);
    if (!_started) {
        _started = YES;
        [self startLocationAndBarometer];
        if (_locationManager.authorizationStatus == kCLAuthorizationStatusNotDetermined) [self requestLocation];
    }
    return YES;
}

- (void)updateMotion:(CMDeviceMotion *)sample {
    if (!_active || !sample) return;
    CMAcceleration g = sample.gravity;
    simd_double3 gravity = {-g.x, -g.y, -g.z};
    if (simd_length(gravity) < .1) return;
    _up = simd_normalize(gravity);
    _attitude = sample;
    if (!_calibrated) return;
    double pitch = Degrees * asin(fmax(-1, fmin(1, simd_dot(_up, _forward))));
    double roll = Degrees * atan2(-simd_dot(_up, _right), simd_dot(_up, _referenceUp));
    _pitch += .22 * (pitch - _pitch);
    _roll = remainder(_roll + .22 * remainder(roll - _roll, 360), 360);
    CMAcceleration a = sample.userAcceleration;
    simd_double3 linear = {a.x, a.y, a.z};
    double accel = 9.80665 * simd_dot(linear, _forward);
    double lateral = simd_dot(linear, _right);
    _acceleration = isfinite(_acceleration) ? _acceleration + .16 * (accel - _acceleration) : accel;
    _lateralG = isfinite(_lateralG) ? _lateralG + .16 * (lateral - _lateralG) : lateral;
    if (_recordingPeaks) {
        _peakPitch = higherPeak(_peakPitch, _pitch);
        _minimumPitch = fmin(_minimumPitch, _pitch);
        _peakLeftG = higherPeak(_peakLeftG, -_lateralG);
        _peakRightG = higherPeak(_peakRightG, _lateralG);
    }
}

- (void)startLocationAndBarometer {
    [self updateLocationAuthorization];
    if ([CMAltimeter isRelativeAltitudeAvailable] && _active) {
        __weak typeof(self) weakSelf = self;
        [_altimeter startRelativeAltitudeUpdatesToQueue:NSOperationQueue.mainQueue
                                            withHandler:^(CMAltitudeData *data, NSError *error) {
            [weakSelf updateBarometer:data];
        }];
    }
}

- (void)updateBarometer:(CMAltitudeData *)data {
    if (!_active || !data) return;
    _relativeAltitude = data.relativeAltitude.doubleValue;
    _barometerTime = NSProcessInfo.processInfo.systemUptime;
    [self recordAltitudeExtrema];
}

- (void)recordAltitudeExtrema {
    if (!_recordingPeaks) return;
    double value = self.altitude;
    if (!isfinite(value)) return;
    _peakAltitude = fmax(_peakAltitude, value);
    _minimumAltitude = fmin(_minimumAltitude, value);
}

- (void)requestLocation {
    if (_locationManager.authorizationStatus == kCLAuthorizationStatusNotDetermined) {
        [_locationManager requestWhenInUseAuthorization];
    } else if (!self.preciseLocation) {
        NSURL *url = [NSURL URLWithString:UIApplicationOpenSettingsURLString];
        [UIApplication.sharedApplication openURL:url options:@{} completionHandler:nil];
    }
}

- (BOOL)preciseLocation {
    CLAuthorizationStatus status = _locationManager.authorizationStatus;
    return (status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways)
            && _locationManager.accuracyAuthorization == CLAccuracyAuthorizationFullAccuracy;
}

- (void)updateLocationAuthorization {
    if (!_active || !_started) return;
    if (self.preciseLocation) [_locationManager startUpdatingLocation];
    else {
        [_locationManager stopUpdatingLocation];
        _location = nil;
        _altitudeTime = 0;
        _barometricAnchor = _mslAnchor = NAN;
    }
    if ([CLLocationManager headingAvailable]) [_locationManager startUpdatingHeading];
}

- (void)locationManagerDidChangeAuthorization:(CLLocationManager *)manager {
    [self updateLocationAuthorization];
}

- (void)locationManager:(CLLocationManager *)manager didUpdateLocations:(NSArray<CLLocation *> *)locations {
    CLLocation *fix = locations.lastObject;
    if (!_active || !self.preciseLocation || !fix || fix.horizontalAccuracy < 0 ||
        !CLLocationCoordinate2DIsValid(fix.coordinate) || fabs(fix.timestamp.timeIntervalSinceNow) > FixTimeout ||
        (_location && [fix.timestamp compare:_location.timestamp] != NSOrderedDescending)) return;
    _location = fix;
    if (self.courseAvailable) _lastCourse = fix.course;
    if (_recordingPeaks) _peakSpeed = higherPeak(_peakSpeed, self.speed);
    if (fix.verticalAccuracy < 0 || !isfinite(fix.altitude)) return;
    double estimate = self.usingBarometer ? self.altitude : NAN;
    NSTimeInterval now = NSProcessInfo.processInfo.systemUptime;
    _mslAnchor = isfinite(estimate) && now - _altitudeTime < FixTimeout
        ? estimate + .12 * (fix.altitude - estimate) : fix.altitude;
    _altitudeTime = now;
    _barometricAnchor = now - _barometerTime < 3 ? _relativeAltitude : NAN;
    [self recordAltitudeExtrema];
}

- (void)locationManager:(CLLocationManager *)manager didUpdateHeading:(CLHeading *)heading {
    if (heading.headingAccuracy >= 0) _magnetic = heading;
    else _magnetic = nil;
}

- (CLLocation *)fix {
    return _location && fabs(_location.timestamp.timeIntervalSinceNow) <= FixTimeout ? _location : nil;
}

- (double)speed {
    CLLocation *fix = self.fix;
    return fix && fix.speed >= 0 ? 3.6 * fix.speed : NAN;
}

- (BOOL)courseAvailable {
    CLLocation *fix = self.fix;
    return fix && fix.speed >= 1.4 && fix.course >= 0 && (fix.courseAccuracy < 0 || fix.courseAccuracy <= 35);
}

- (double)heading {
    if (self.courseAvailable) return self.fix.course;
    return self.speed >= 5.04 ? NAN : [self headingFromCompass:NO];
}

- (double)mapCourse {
    if (isfinite(_lastCourse)) return _lastCourse;
    return [self headingFromCompass:YES];
}

- (double)headingFromCompass:(BOOL)trueNorth {
    if (!_calibrated || !_magnetic || !_attitude ||
        fabs(_magnetic.timestamp.timeIntervalSinceNow) > FixTimeout) return NAN;
    // Core Location's default reference is the portrait top edge of the phone.
    simd_double3 top = {0, 1, 0};
    top -= simd_dot(top, _up) * _up;
    simd_double3 front = _forward - simd_dot(_forward, _up) * _up;
    if (simd_length(top) < .2 || simd_length(front) < .2) return NAN;
    double delta = Degrees * atan2(simd_dot(_up, simd_cross(front, top)), simd_dot(front, top));
    double heading = trueNorth ? _magnetic.trueHeading : _magnetic.magneticHeading;
    return heading >= 0 ? fmod(heading + delta + 360, 360) : NAN;
}

- (BOOL)usingBarometer {
    NSTimeInterval now = NSProcessInfo.processInfo.systemUptime;
    return isfinite(_barometricAnchor) && now - _barometerTime < 3 && now - _altitudeTime <= 30;
}

- (double)altitude {
    if (!self.preciseLocation) return NAN;
    if (self.usingBarometer) return _mslAnchor + _relativeAltitude - _barometricAnchor;
    return NSProcessInfo.processInfo.systemUptime - _altitudeTime <= FixTimeout ? _mslAnchor : NAN;
}
@end
