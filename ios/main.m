#import "HUDView.h"
#import "HUDMap.h"

static UIColor *green(void) { return [UIColor colorWithRed:.38 green:1 blue:.60 alpha:1]; }
static UIColor *background(void) { return [UIColor colorWithRed:.012 green:.05 blue:.035 alpha:1]; }

@interface HUDController : UIViewController
- (void)activate;
- (void)deactivate;
@end

@implementation HUDController {
    HUDTelemetry *_telemetry;
    HUDView *_hud;
    HUDMap *_map;
    UIView *_calibration;
    UILabel *_instructions;
    UIButton *_go, *_zero, *_location;
    CADisplayLink *_displayLink;
    UIInterfaceOrientation _calibratedOrientation;
    BOOL _calibrating;
}

- (BOOL)prefersStatusBarHidden { return YES; }
- (BOOL)prefersHomeIndicatorAutoHidden { return YES; }
- (UIRectEdge)preferredScreenEdgesDeferringSystemGestures { return UIRectEdgeBottom; }

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = background();
    _telemetry = [HUDTelemetry new];
    _hud = [[HUDView alloc] initWithFrame:CGRectZero];
    _hud.telemetry = _telemetry;
    [self.view addSubview:_hud];
    _map = [[HUDMap alloc] initWithFrame:CGRectZero];
    [self.view addSubview:_map];
    _zero = [self button:@"ZERO" action:@selector(resetSession)];
    _zero.accessibilityLabel = @"Recalibrate and clear session peaks";
    _location = [self button:@"ALLOW PRECISE LOCATION" action:@selector(requestLocation)];
    _location.hidden = YES;
    _calibration = [UIView new];
    _calibration.backgroundColor = [UIColor colorWithRed:.027 green:.106 blue:.071 alpha:1];
    _calibration.layer.cornerRadius = 8;
    _calibration.layer.borderColor = [green() colorWithAlphaComponent:.35].CGColor;
    _calibration.layer.borderWidth = 1;
    [self.view addSubview:_calibration];

    UILabel *title = [self label:@"ZERO PITCH & ROLL" size:15 bold:YES];
    title.frame = CGRectMake(12, 13, 276, 22);
    [_calibration addSubview:title];
    _instructions = [self label:@"Waiting for motion sensors…" size:13 bold:NO];
    _instructions.textColor = [green() colorWithAlphaComponent:.82];
    _instructions.frame = CGRectMake(12, 41, 276, 24);
    [_calibration addSubview:_instructions];
    _go = [self button:@"GO" action:@selector(calibrate)];
    _go.frame = CGRectMake(100, 76, 100, 44);
    _go.backgroundColor = green();
    [_go setTitleColor:background() forState:UIControlStateNormal];
    [_calibration addSubview:_go];
    [self showCalibration];
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    CGRect b = UIEdgeInsetsInsetRect(self.view.bounds, self.view.safeAreaInsets);
    BOOL portrait = b.size.height >= b.size.width;
    CGFloat hudHeight = portrait ? floor(b.size.height * 2 / 3) : b.size.height;
    CGFloat hudWidth = portrait ? b.size.width : floor(b.size.width * 2 / 3);
    CGRect hudBounds = CGRectMake(b.origin.x, b.origin.y, hudWidth, hudHeight);
    _hud.portrait = portrait;
    _hud.frame = hudBounds;
    _map.portrait = portrait;
    _map.frame = portrait ? CGRectMake(b.origin.x, CGRectGetMaxY(hudBounds), b.size.width, b.size.height - hudHeight)
                          : CGRectMake(CGRectGetMaxX(hudBounds), b.origin.y, b.size.width - hudWidth, b.size.height);
    [_map setNeedsLayout];
    _calibration.bounds = CGRectMake(0, 0, 300, 132);
    _calibration.center = CGPointMake(CGRectGetMidX(hudBounds), CGRectGetMidY(hudBounds));
    _zero.frame = CGRectMake(CGRectGetMaxX(hudBounds) - 82, b.origin.y + 4, 74, 44);
    _location.frame = CGRectMake(b.origin.x + 8, CGRectGetMaxY(hudBounds) - 50, 230, 44);
    [self refresh];
}

- (UIButton *)button:(NSString *)title action:(SEL)action {
    UIButton *button = [UIButton buttonWithType:UIButtonTypeSystem];
    button.backgroundColor = [UIColor colorWithRed:.063 green:.173 blue:.125 alpha:1];
    button.layer.cornerRadius = 6;
    button.layer.borderWidth = 1;
    button.layer.borderColor = [green() colorWithAlphaComponent:.24].CGColor;
    button.titleLabel.font = [UIFont monospacedSystemFontOfSize:13 weight:UIFontWeightBold];
    [button setTitle:title forState:UIControlStateNormal];
    [button setTitleColor:green() forState:UIControlStateNormal];
    [button addTarget:self action:action forControlEvents:UIControlEventTouchUpInside];
    [self.view addSubview:button];
    return button;
}

- (UILabel *)label:(NSString *)text size:(CGFloat)size bold:(BOOL)bold {
    UILabel *label = [UILabel new];
    label.text = text;
    label.textAlignment = NSTextAlignmentCenter;
    label.textColor = green();
    label.font = bold ? [UIFont monospacedSystemFontOfSize:size weight:UIFontWeightBold]
                      : [UIFont systemFontOfSize:size];
    return label;
}

- (void)activate {
    [self loadViewIfNeeded];
    if (_displayLink) return;
    UIApplication.sharedApplication.idleTimerDisabled = YES;
    [_telemetry resume];
    _displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(displayFrame:)];
    _displayLink.preferredFramesPerSecond = 30;
    [_displayLink addToRunLoop:NSRunLoop.mainRunLoop forMode:NSRunLoopCommonModes];
}

- (void)deactivate {
    [_displayLink invalidate];
    _displayLink = nil;
    [_telemetry pause];
    [_map showFix:nil course:NAN message:@"Waiting for a GNSS fix"];
    UIApplication.sharedApplication.idleTimerDisabled = NO;
}

- (void)showCalibration {
    [_telemetry suspendAttitude];
    _calibrating = YES;
    _zero.hidden = YES;
    _calibration.hidden = NO;
    [self refresh];
}

- (void)resetSession {
    [_telemetry resetPeaks];
    [self showCalibration];
}

- (void)calibrate {
    UIInterfaceOrientation orientation = self.view.window.windowScene.interfaceOrientation;
    if (![_telemetry calibrate:orientation]) return;
    _calibratedOrientation = orientation;
    _calibrating = NO;
    _zero.hidden = NO;
    _calibration.hidden = YES;
    [self refresh];
}

- (void)requestLocation { [_telemetry requestLocation]; }
- (void)displayFrame:(CADisplayLink *)link { [self refresh]; }

- (void)refresh {
    UIInterfaceOrientation orientation = self.view.window.windowScene.interfaceOrientation;
    if (_telemetry.started && !_calibrating && orientation != UIInterfaceOrientationUnknown &&
        orientation != _calibratedOrientation) {
        [self showCalibration];
        return;
    }
    if (_calibrating) {
        _go.enabled = _telemetry.ready;
        _go.alpha = _go.enabled ? 1 : .45;
        _instructions.text = _go.enabled ? @"Park and secure your phone in its mount." : @"Waiting for motion sensors…";
    }
    BOOL needsLocation = _telemetry.started && !_telemetry.preciseLocation;
    CLAuthorizationStatus access = _telemetry.locationManager.authorizationStatus;
    _location.hidden = !needsLocation;
    NSString *buttonTitle = access == kCLAuthorizationStatusDenied ? @"ENABLE LOCATION" : @"ALLOW PRECISE LOCATION";
    if (![_location.currentTitle isEqualToString:buttonTitle]) [_location setTitle:buttonTitle forState:UIControlStateNormal];
    CLLocation *fix = _telemetry.fix;
    _hud.status = !_telemetry.started ? @"CALIBRATE" : needsLocation ? @"LOCATION PERMISSION"
                    : fix ? @"GNSS FIX" : @"WAITING FOR GNSS";
    _hud.attitudeEnabled = _telemetry.started && !_calibrating;
    [_hud setNeedsDisplay];
    if (_map.isMapEnabled) {
        NSString *message = needsLocation ? @"Location unavailable" : _telemetry.started ? @"Waiting for a GNSS fix" : @"Tap GO to start";
        [_map showFix:fix course:_telemetry.mapCourse message:message];
    }
}
@end

@interface HUDSceneDelegate : UIResponder <UIWindowSceneDelegate>
@property(nonatomic) UIWindow *window;
@end

@implementation HUDSceneDelegate
- (void)scene:(UIScene *)scene willConnectToSession:(UISceneSession *)session options:(UISceneConnectionOptions *)options {
    if (![scene isKindOfClass:UIWindowScene.class]) return;
    self.window = [[UIWindow alloc] initWithWindowScene:(UIWindowScene *)scene];
    self.window.rootViewController = [HUDController new];
    [self.window makeKeyAndVisible];
}
- (void)sceneDidBecomeActive:(UIScene *)scene { [(HUDController *)self.window.rootViewController activate]; }
- (void)sceneWillResignActive:(UIScene *)scene { [(HUDController *)self.window.rootViewController deactivate]; }
@end

@interface HUDAppDelegate : UIResponder <UIApplicationDelegate>
@end
@implementation HUDAppDelegate
- (UISceneConfiguration *)application:(UIApplication *)application
         configurationForConnectingSceneSession:(UISceneSession *)session options:(UISceneConnectionOptions *)options {
    UISceneConfiguration *configuration = [[UISceneConfiguration alloc] initWithName:@"Car HUD" sessionRole:session.role];
    configuration.delegateClass = HUDSceneDelegate.class;
    return configuration;
}
@end

int main(int argc, char **argv) {
    @autoreleasepool {
        return UIApplicationMain(argc, argv, nil, NSStringFromClass(HUDAppDelegate.class));
    }
}
