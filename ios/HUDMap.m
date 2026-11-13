#import "HUDMap.h"
#import <MapKit/MapKit.h>

static NSString *const MapEnabledKey = @"mapEnabled";

@interface HUDTiles : MKTileOverlay
@property(nonatomic) NSURLSession *session;
@end

@implementation HUDTiles
- (instancetype)init {
    if (!(self = [super initWithURLTemplate:@"https://tile.openstreetmap.org/{z}/{x}/{y}.png"])) return nil;
    self.canReplaceMapContent = YES;
    self.maximumZ = 19;
    NSURLSessionConfiguration *config = NSURLSessionConfiguration.defaultSessionConfiguration;
    config.HTTPAdditionalHeaders = @{@"User-Agent": @"CarHUD/1.4 (iOS; app.carhud.ios)"};
    static NSURLCache *cache;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSURL *directory = [NSFileManager.defaultManager URLsForDirectory:NSCachesDirectory
                                                               inDomains:NSUserDomainMask].firstObject;
        cache = [[NSURLCache alloc] initWithMemoryCapacity:8 * 1024 * 1024 diskCapacity:64 * 1024 * 1024
                                              directoryURL:[directory URLByAppendingPathComponent:@"OSM" isDirectory:YES]];
    });
    config.URLCache = cache;
    config.timeoutIntervalForRequest = 12;
    self.session = [NSURLSession sessionWithConfiguration:config];
    return self;
}
- (void)loadTileAtPath:(MKTileOverlayPath)path result:(void (^)(NSData *, NSError *))result {
    NSURLSessionDataTask *task = [self.session dataTaskWithURL:[self URLForTilePath:path]
                                           completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
        NSInteger code = [(NSHTTPURLResponse *)response statusCode];
        if (code == 200 && data.length) result(data, nil);
        else result(nil, error ?: [NSError errorWithDomain:NSURLErrorDomain code:NSURLErrorBadServerResponse userInfo:nil]);
    }];
    [task resume];
}
- (void)dealloc { [_session invalidateAndCancel]; }
@end

@interface HUDMarker : UIView
@end
@implementation HUDMarker
- (void)drawRect:(CGRect)rect {
    CGContextRef c = UIGraphicsGetCurrentContext();
    CGContextTranslateCTM(c, CGRectGetMidX(self.bounds), CGRectGetMidY(self.bounds));
    CGContextSetRGBFillColor(c, .03, .15, .10, 1);
    CGContextFillEllipseInRect(c, CGRectMake(-14, -14, 28, 28));
    CGContextSetRGBFillColor(c, .34, 1, .62, 1);
    CGContextMoveToPoint(c, 0, -11);
    CGContextAddLineToPoint(c, 8, 8);
    CGContextAddLineToPoint(c, 0, 4);
    CGContextAddLineToPoint(c, -8, 8);
    CGContextClosePath(c);
    CGContextFillPath(c);
}
@end

@interface HUDMap () <MKMapViewDelegate>
@end

@implementation HUDMap {
    MKMapView *_map;
    HUDTiles *_tiles;
    HUDMarker *_marker;
    UILabel *_status;
    UIButton *_credit, *_plus, *_minus;
    UIView *_separator;
    UIView *_content, *_switchPanel;
    UISwitch *_mapSwitch;
    CLLocation *_center;
    double _meters, _course;
}
@synthesize mapEnabled = _mapEnabled;

- (instancetype)initWithFrame:(CGRect)frame {
    if (!(self = [super initWithFrame:frame])) return nil;
    self.backgroundColor = UIColor.blackColor;
    self.clipsToBounds = YES;
    _content = [UIView new];
    _content.backgroundColor = [UIColor colorWithRed:.047 green:.11 blue:.09 alpha:1];
    [_content setClipsToBounds:YES];
    [self addSubview:_content];
    _meters = 580;
    _status = [UILabel new];
    _status.textAlignment = NSTextAlignmentCenter;
    _status.textColor = UIColor.whiteColor;
    _status.font = [UIFont systemFontOfSize:14];
    _status.numberOfLines = 0;
    [_content addSubview:_status];
    _separator = [UIView new];
    _separator.backgroundColor = [UIColor colorWithRed:.28 green:.58 blue:.42 alpha:1];
    [_content addSubview:_separator];
    _credit = [self button:@"© OpenStreetMap contributors" action:@selector(openCredit)];
    _credit.titleLabel.font = [UIFont systemFontOfSize:11];
    _minus = [self button:@"−" action:@selector(zoomOut)];
    _minus.accessibilityLabel = @"Zoom out";
    _plus = [self button:@"+" action:@selector(zoomIn)];
    _plus.accessibilityLabel = @"Zoom in";
    _marker = [HUDMarker new];
    _marker.backgroundColor = UIColor.clearColor;
    _marker.opaque = NO;
    [_content addSubview:_marker];
    _marker.hidden = YES;

    _switchPanel = [UIView new];
    _switchPanel.backgroundColor = [UIColor colorWithRed:.07 green:.17 blue:.13 alpha:.96];
    _switchPanel.layer.cornerRadius = 6;
    [self addSubview:_switchPanel];
    UILabel *mapLabel = [[UILabel alloc] initWithFrame:CGRectMake(7, 0, 28, 44)];
    mapLabel.text = @"MAP";
    mapLabel.font = [UIFont monospacedSystemFontOfSize:11 weight:UIFontWeightBold];
    mapLabel.textColor = [UIColor colorWithRed:.38 green:1 blue:.6 alpha:1];
    [_switchPanel addSubview:mapLabel];
    _mapSwitch = [[UISwitch alloc] initWithFrame:CGRectMake(36, 6.5, 51, 31)];
    _mapSwitch.accessibilityLabel = @"Map";
    _mapSwitch.onTintColor = [UIColor colorWithRed:.18 green:.72 blue:.40 alpha:1];
    [_mapSwitch addTarget:self action:@selector(toggleMap:) forControlEvents:UIControlEventValueChanged];
    [_switchPanel addSubview:_mapSwitch];
    NSUserDefaults *defaults = NSUserDefaults.standardUserDefaults;
    [defaults registerDefaults:@{MapEnabledKey:@YES}];
    self.mapEnabled = [defaults boolForKey:MapEnabledKey];
    return self;
}

- (void)toggleMap:(UISwitch *)sender {
    self.mapEnabled = sender.isOn;
    [NSUserDefaults.standardUserDefaults setBool:sender.isOn forKey:MapEnabledKey];
}

- (void)setMapEnabled:(BOOL)enabled {
    _mapEnabled = enabled;
    _mapSwitch.on = enabled;
    _content.hidden = !enabled;
    if (!enabled) [self clearMap];
}

- (void)clearMap {
    [_tiles.session invalidateAndCancel];
    _map.delegate = nil;
    [_map removeFromSuperview];
    _map = nil;
    _tiles = nil;
    _center = nil;
    _marker.hidden = YES;
}

- (UIButton *)button:(NSString *)title action:(SEL)action {
    UIButton *button = [UIButton buttonWithType:UIButtonTypeSystem];
    [button setTitle:title forState:UIControlStateNormal];
    [button setTitleColor:UIColor.whiteColor forState:UIControlStateNormal];
    button.backgroundColor = [UIColor colorWithRed:.07 green:.17 blue:.13 alpha:.93];
    button.layer.cornerRadius = 5;
    [button addTarget:self action:action forControlEvents:UIControlEventTouchUpInside];
    [_content addSubview:button];
    return button;
}
- (void)layoutSubviews {
    [super layoutSubviews];
    CGRect b = self.bounds;
    _content.frame = b;
    _map.frame = b;
    _status.frame = CGRectInset(b, MIN(10, b.size.width / 2), 0);
    _separator.frame = self.portrait ? CGRectMake(0, 0, b.size.width, 1) : CGRectMake(0, 0, 1, b.size.height);
    _marker.frame = CGRectMake(CGRectGetMidX(b) - 15, CGRectGetMidY(b) - 15, 30, 30);
    CGFloat creditWidth = MIN(180, b.size.width);
    _credit.frame = CGRectMake(b.size.width - creditWidth, b.size.height - 24, creditWidth, 24);
    _credit.titleLabel.adjustsFontSizeToFitWidth = YES;
    CGFloat zoomTop = b.size.width < 194 ? 56 : 8;
    _minus.frame = CGRectMake(8, zoomTop, 40, 44);
    _plus.frame = CGRectMake(52, zoomTop, 40, 44);
    _switchPanel.frame = CGRectMake(MAX(0,b.size.width - 100), 8, 92, 44);
    [self centerMap];
}
- (void)showFix:(CLLocation *)fix course:(double)course message:(NSString *)message {
    if (!self.isMapEnabled) return;
    if (!fix || !isfinite(course)) {
        if (_map) [self clearMap];
        _status.hidden = NO;
        if (fix) message = @"Waiting for course";
        if (![_status.text isEqualToString:message]) _status.text = message;
        return;
    }
    if (!_map) {
        _map = [[MKMapView alloc] initWithFrame:self.bounds];
        _map.delegate = self;
        _map.pitchEnabled = _map.scrollEnabled = _map.zoomEnabled = NO;
        _map.rotateEnabled = YES;
        _map.userInteractionEnabled = NO;
        _map.showsCompass = _map.showsScale = NO;
        _map.pointOfInterestFilter = MKPointOfInterestFilter.filterExcludingAllCategories;
        _tiles = [HUDTiles new];
        _center = fix;
        _course = course;
        [self centerMap];
        [_map addOverlay:_tiles level:MKOverlayLevelAboveLabels];
        [_content insertSubview:_map atIndex:0];
    } else if (_center != fix || fabs(remainder(course - _course, 360)) >= .5) {
        _center = fix;
        _course = course;
        [self centerMap];
    }
    _status.hidden = YES;
    _marker.hidden = NO;
}
- (void)centerMap {
    if (!_center || !_map || CGRectIsEmpty(_map.bounds)) return;
    MKMapCamera *camera = [MKMapCamera cameraLookingAtCenterCoordinate:_center.coordinate
                                                   fromDistance:_meters pitch:0 heading:_course];
    [_map setCamera:camera animated:NO];
}
- (void)zoomIn { _meters = MAX(70, _meters / 2); [self centerMap]; }
- (void)zoomOut { _meters = MIN(180000, _meters * 2); [self centerMap]; }
- (void)openCredit {
    [UIApplication.sharedApplication openURL:[NSURL URLWithString:@"https://www.openstreetmap.org/copyright"]
                                        options:@{} completionHandler:nil];
}
- (MKOverlayRenderer *)mapView:(MKMapView *)mapView rendererForOverlay:(id<MKOverlay>)overlay {
    return [[MKTileOverlayRenderer alloc] initWithTileOverlay:(MKTileOverlay *)overlay];
}
@end
