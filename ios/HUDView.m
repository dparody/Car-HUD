#import "HUDView.h"

static const uint32_t BACK = 0x030d09, GREEN = 0x62ff9a, SOFT = 0x56b27f, GRID = 0x184d34;
static const uint32_t PEAK = 0xff474f;
static const uint32_t MINIMUM = 0x4a9dff;
typedef NS_ENUM(NSInteger, Alignment) { LEFT, CENTER, RIGHT };

static UIColor *color(uint32_t rgb) {
    return [UIColor colorWithRed:((rgb >> 16) & 255) / 255.0
                           green:((rgb >> 8) & 255) / 255.0 blue:(rgb & 255) / 255.0 alpha:1];
}
static NSString *fmt(NSString *pattern, ...) NS_FORMAT_FUNCTION(1,2);
static NSString *fmt(NSString *pattern, ...) {
    static NSLocale *locale;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ locale = [[NSLocale alloc] initWithLocaleIdentifier:@"en_US_POSIX"]; });
    va_list args;
    va_start(args, pattern);
    NSString *result = [[NSString alloc] initWithFormat:pattern locale:locale arguments:args];
    va_end(args);
    return result;
}
static void stroke(CGContextRef c, uint32_t rgb, CGFloat width) {
    CGContextSetStrokeColorWithColor(c, color(rgb).CGColor);
    CGContextSetLineWidth(c, width);
}
static void line(CGContextRef c, CGFloat x, CGFloat y, CGFloat x2, CGFloat y2, uint32_t rgb, CGFloat width) {
    stroke(c, rgb, width);
    CGContextMoveToPoint(c, x, y);
    CGContextAddLineToPoint(c, x2, y2);
    CGContextStrokePath(c);
}
static void clip(CGContextRef c, CGFloat left, CGFloat top, CGFloat right, CGFloat bottom) {
    CGContextClipToRect(c, CGRectMake(left, top, right - left, bottom - top));
}
static void rotate(CGContextRef c, CGFloat degrees, CGFloat x, CGFloat y) {
    CGContextTranslateCTM(c, x, y);
    CGContextRotateCTM(c, degrees * M_PI / 180);
    CGContextTranslateCTM(c, -x, -y);
}
static void label(CGContextRef c, NSString *text, CGFloat x, CGFloat y, CGFloat size, uint32_t rgb, Alignment alignment) {
    UIFont *font = [UIFont monospacedSystemFontOfSize:size weight:UIFontWeightSemibold];
    NSDictionary *attributes = @{NSFontAttributeName:font, NSForegroundColorAttributeName:color(rgb)};
    if (alignment != LEFT) x -= [text sizeWithAttributes:attributes].width / (alignment == CENTER ? 2 : 1);
    [text drawAtPoint:CGPointMake(x, y - font.ascender) withAttributes:attributes];
}
static double angleDelta(double first, double second) { return remainder(first - second, 360); }
static double approach(double old, double value, double gain) {
    return isfinite(old) && isfinite(value) ? old + gain * (value - old) : value;
}
static NSString *const CARDINAL[] = {@"N", @"E", @"S", @"W"};

static void tapeMarker(CGContextRef c, float axis, int direction, float y, float top, float bottom, uint32_t rgb) {
    if (y >= top && y <= bottom) line(c, axis, y, axis + direction * 12, y, rgb, 1.5f);
}

static void tape(CGContextRef c, BOOL speed, float value, double peak, double minimum, float edge, float axis,
                 float midY, BOOL portrait, BOOL barometer) {
    int direction = speed ? -1 : 1;
    int step = speed ? 5 : 10;
    int major = speed ? 10 : 20;
    float center = isfinite(value) ? (speed ? fmin(250, value) : value) : 0;
    float spacing = portrait ? (speed ? 2.4f : 1.2f) : (speed ? 1.7f : 0.9f);
    float top = portrait ? 137 : 80;
    float bottom = portrait ? 313 : 200;
    Alignment align = speed ? LEFT : RIGHT;
    label(c, speed ? (portrait ? @"SPEED" : @"GROUND SPEED") : @"ALTITUDE MSL", edge, top - 22, 13, SOFT, align);
    label(c, speed ? @"km/h" : @"m", edge, top - 5, 11, SOFT, align);
    CGContextSaveGState(c);
    clip(c, fmin(edge, axis) - 4, top, fmax(edge, axis) + 4, bottom);
    line(c, axis, top, axis, bottom, GRID, 1);
    if (isfinite(value)) {
        int first = (int) floor((center + (midY - bottom) / spacing) / step) * step;
        int last = (int) ceil((center + (midY - top) / spacing) / step) * step;
        for (int tick = first; tick <= last; tick += step) {
            if (speed && (tick < 0 || tick > 250)) continue;
            float y = midY - (tick - center) * spacing;
            BOOL marked = tick % major == 0;
            line(c, axis, y, axis + direction * (marked ? 12 : 6), y, marked ? GREEN : SOFT, 1.5f);
            if (marked && fabs(y - midY) > 20 && y > top + 7 && y < bottom - 6) {
                label(c, fmt(@"%d", tick), axis + direction * 20, y + 4, 12, SOFT,
                        speed ? RIGHT : LEFT);
            }
        }
    }
    CGContextRestoreGState(c);
    CGContextBeginPath(c);
    CGContextMoveToPoint(c, edge, midY - 14);
    CGContextAddLineToPoint(c, axis + direction * 9, midY - 14);
    CGContextAddLineToPoint(c, axis, midY);
    CGContextAddLineToPoint(c, axis + direction * 9, midY + 14);
    CGContextAddLineToPoint(c, edge, midY + 14);
    CGContextClosePath(c);
    stroke(c, GREEN, 1.6);
    CGContextSetFillColorWithColor(c, color(BACK).CGColor);
    CGContextDrawPath(c, kCGPathFillStroke);
    NSString *readout = isfinite(value)
            ? (speed && value > 250 ? @"250+" : fmt(@"%ld", lround(value))) : @"—";
    label(c, readout, (axis + edge) / 2 + direction * 3, midY + 7, 22, GREEN, CENTER);
    if (isfinite(value)) {
        CGContextSaveGState(c);
        clip(c, fmin(edge, axis) - 4, top, fmax(edge, axis) + 4, bottom);
        float peakY = midY - (float)(peak - center) * spacing;
        float minimumY = speed ? INFINITY : midY - (float)(minimum - center) * spacing;
        if (fabsf(peakY - minimumY) < 1.5f) {
            float midpoint = (peakY + minimumY) / 2;
            peakY = midpoint - .75f;
            minimumY = midpoint + .75f;
        }
        if (!speed || peak <= 250) {
            tapeMarker(c, axis, direction, peakY, top, bottom, PEAK);
        }
        if (!speed) {
            tapeMarker(c, axis, direction, minimumY, top, bottom, MINIMUM);
        }
        CGContextRestoreGState(c);
    }
    if (!speed && isfinite(value)) {
        label(c, barometer ? @"GNSS + BARO" : @"GNSS", edge, bottom + 13, 10, SOFT, align);
    }
}

static void pitchLine(CGContextRef c, double degrees, float midX, float y, uint32_t rgb, BOOL portrait) {
    BOOL horizon = degrees == 0;
    float outer = portrait ? (horizon ? 99 : 62) : (horizon ? 186 : 112);
    float inner = horizon ? 29 : portrait ? 28 : 49;
    for (int side = -1; side <= 1; side += 2) {
        float a = midX + side * outer;
        float b = midX + side * inner;
        if (degrees < 0) {
            float third = (b - a) / 3;
            line(c, a, y, a + third, y, rgb, 1.6f);
            line(c, b - third, y, b, y, rgb, 1.6f);
        } else {
            line(c, a, y, b, y, rgb, horizon ? 2 : 1.6f);
        }
        if (!horizon && rgb == SOFT) {
            line(c, a, y, a, y + (degrees > 0 ? 6 : -6), rgb, 1.5f);
            label(c, fmt(@"%+d", (int)degrees), a + side * 9, y + 4, 12, rgb, side < 0 ? RIGHT : LEFT);
        }
    }
}

static void attitude(CGContextRef c, double pitch, double roll, double peak, double minimum,
                     float midX, float midY, BOOL portrait) {
    CGContextSaveGState(c);
    float halfWidth = portrait ? 112 : 220;
    clip(c, midX - halfWidth, portrait ? 136 : 62, midX + halfWidth, portrait ? 316 : 184);
    if (isfinite(pitch)) {
        rotate(c, -roll, midX, midY);
        float spacing = portrait ? 7.5f : 5.5f;
        for (int mark = -90; mark <= 90; mark += 5) {
            float y = midY + (float)(pitch - mark) * spacing;
            if (fabs(y - midY) > (portrait ? 140 : 225)) continue;
            pitchLine(c, mark, midX, y, mark == 0 ? GREEN : SOFT, portrait);
        }
        if (isfinite(peak)) {
            pitchLine(c, peak, midX, midY + (float)(pitch - peak) * spacing, PEAK, portrait);
        }
        if (minimum < 0) {
            pitchLine(c, minimum, midX, midY + (float)(pitch - minimum) * spacing, MINIMUM, portrait);
        }
    }
    CGContextRestoreGState(c);
    for (int side = -1; side <= 1; side += 2) {
        line(c, midX + side * 54, midY, midX + side * 24, midY, GREEN, 2);
        line(c, midX + side * 24, midY, midX + side * 18, midY + 5, GREEN, 2);
    }
    stroke(c, GREEN, 1.8);
    CGContextStrokeEllipseInRect(c, CGRectMake(midX-5, midY-5, 10, 10));
    label(c, isfinite(pitch) ? fmt(@"PITCH %+.0f°", fabs(pitch) < 0.5 ? 0 : pitch)
            : @"PITCH —", midX, portrait ? 126 : 56, portrait ? 13 : 11, SOFT, CENTER);
}

static void lateralTick(CGContextRef c, float pivotX, float pivotY, double angle, BOOL major, uint32_t rgb) {
    CGContextSaveGState(c);
    rotate(c, angle, pivotX, pivotY);
    line(c, pivotX, pivotY + (major ? 45 : 49), pivotX, pivotY + 55, rgb, 1.5f);
    CGContextRestoreGState(c);
}

static void lateralScale(CGContextRef c, double g, double leftPeak, double rightPeak,
                         float pivotX, float pivotY, BOOL portrait) {
    for (int mark = -60; mark <= 60; mark += 10) {
        lateralTick(c, pivotX, pivotY, mark, mark % 30 == 0, mark == 0 ? GREEN : SOFT);
    }
    if (leftPeak >= .005 && leftPeak <= 2) lateralTick(c, pivotX, pivotY, 30 * leftPeak, YES, PEAK);
    if (rightPeak >= .005 && rightPeak <= 2) lateralTick(c, pivotX, pivotY, -30 * rightPeak, YES, PEAK);
    if (isfinite(g)) {
        CGContextSaveGState(c);
        rotate(c, -30 * fmax(-2, fmin(2, g)), pivotX, pivotY);
        line(c, pivotX - 4, pivotY + 41, pivotX, pivotY + 46, GREEN, 1.5f);
        line(c, pivotX, pivotY + 46, pivotX + 4, pivotY + 41, GREEN, 1.5f);
        CGContextRestoreGState(c);
    }
    label(c, @"2L", pivotX - 56, pivotY + 31, 10, SOFT, RIGHT);
    label(c, @"2R", pivotX + 56, pivotY + 31, 10, SOFT, LEFT);
    NSString *value = !isfinite(g) ? @"—" : fabs(g) < .005 ? @"0.00 g"
            : fmt(@"%.2f g %@", fabs(g), g < 0 ? @"L" : @"R");
    label(c, [@"SIDE " stringByAppendingString:value], pivotX, pivotY + 67, portrait ? 14 : 12, GREEN, CENTER);
}

static void compass(CGContextRef c, double heading, BOOL course, float midX, float y) {
    float left = midX - 150;
    float right = midX + 150;
    line(c, left, y, right, y, GRID, 1);
    CGContextSaveGState(c);
    clip(c, left, y - 10, right, y + 27);
    if (isfinite(heading)) {
        for (int tick = 0; tick < 360; tick += 5) {
            float x = midX + (float) angleDelta(tick, heading) * 3.1f;
            if (x < left - 16 || x > right + 16) continue;
            line(c, x, y, x, y + (tick % 10 == 0 ? 11 : 6), SOFT, 1.5f);
            if (tick % 10 == 0) {
                NSString *value = tick % 90 == 0 ? CARDINAL[tick / 90]
                        : fmt(@"%02d", tick / 10);
                label(c, value, x, y + 24, 12, SOFT, CENTER);
            }
        }
    }
    CGContextRestoreGState(c);
    line(c, midX, y - 10, midX, y + 9, GREEN, 2);
    NSString *bearing = isfinite(heading) ? fmt(@"%03ld°", lround(heading) % 360) : @"—";
    CGRect box = CGRectMake(midX - 33, y + 15, 66, 25);
    CGContextSetFillColorWithColor(c, color(BACK).CGColor);
    CGContextFillRect(c, box);
    stroke(c, GREEN, 1.4);
    CGContextStrokeRect(c, box);
    label(c, bearing, midX, y + 34, 18, GREEN, CENTER);
    label(c, !isfinite(heading) ? @"COURSE" : course ? @"COURSE · TRUE" : @"HEADING · MAG",
            midX, y + 51, 11, SOFT, CENTER);
}


@implementation HUDView {
    double _displaySpeed, _displayAltitude, _displayHeading;
    BOOL _wasCourse;
}
- (instancetype)initWithFrame:(CGRect)frame {
    if (!(self = [super initWithFrame:frame])) return nil;
    self.backgroundColor = color(BACK);
    self.opaque = YES;
    self.contentMode = UIViewContentModeRedraw;
    _displaySpeed = _displayAltitude = _displayHeading = NAN;
    return self;
}

- (void)drawRect:(CGRect)dirtyRect {
    CGContextRef c = UIGraphicsGetCurrentContext();
    CGContextSetFillColorWithColor(c, color(BACK).CGColor);
    CGContextFillRect(c, self.bounds);
    BOOL portrait = self.portrait;
    NSString *status = self.status ?: @"CALIBRATE";
    float width = portrait ? 420 : 720;
    float height = portrait ? 580 : 300;
    float midX = width / 2;
    float midY = portrait ? 219 : 137;
    float scale = fmin(self.bounds.size.width / width, self.bounds.size.height / height);
    CGContextSaveGState(c);
    CGContextTranslateCTM(c, (self.bounds.size.width - width * scale) / 2,
                         (self.bounds.size.height - height * scale) / 2);
    CGContextScaleCTM(c, scale, scale);
    BOOL live = self.attitudeEnabled;
    BOOL course = self.telemetry.courseAvailable;
    _displaySpeed = approach(_displaySpeed, self.telemetry.speed, .24);
    _displayAltitude = approach(_displayAltitude, self.telemetry.altitude, .23);
    double heading = course || live ? self.telemetry.heading : NAN;
    if (course != _wasCourse || !isfinite(heading) || !isfinite(_displayHeading)) {
        _displayHeading = heading;
    } else {
        _displayHeading = fmod(_displayHeading + .26 * angleDelta(heading, _displayHeading) + 360, 360);
    }
    _wasCourse = course;

    float left = portrait ? 16 : 24;
    float right = width - left;
    label(c, @"CAR HUD", left, portrait ? 35 : 27, portrait ? 20 : 15, GREEN, LEFT);
    label(c, status, portrait ? midX : 545, portrait ? 65 : 27, portrait ? 13 : 11, SOFT,
            portrait ? CENTER : RIGHT);
    float headerRule = portrait ? 78 : 38;
    line(c, left, headerRule, right, headerRule, GRID, 1);
    tape(c, YES, _displaySpeed, self.telemetry.peakSpeed, NAN, left, portrait ? 90 : 122, midY, portrait, NO);
    tape(c, NO, _displayAltitude, self.telemetry.peakAltitude, self.telemetry.minimumAltitude,
         right, width - (portrait ? 90 : 122),
         midY, portrait, self.telemetry.usingBarometer);
    double roll = live ? self.telemetry.roll : NAN;
    attitude(c, live ? self.telemetry.pitch : NAN, roll, self.telemetry.peakPitch, self.telemetry.minimumPitch,
             midX, midY, portrait);
    compass(c, _displayHeading, course, midX, portrait ? 382 : 200);
    lateralScale(c, live ? self.telemetry.lateralG : NAN, self.telemetry.peakLeftG, self.telemetry.peakRightG,
                 midX, portrait ? 423 : 229, portrait);

    if (portrait) line(c, left, 505, right, 505, GRID, 1);
    label(c, @"ACCEL", left, portrait ? 531 : 245, portrait ? 14 : 12, SOFT, LEFT);
    double acceleration = live ? self.telemetry.acceleration : NAN;
    label(c, isfinite(acceleration) ? fmt(@"%+.1f", fabs(acceleration) < 0.05 ? 0 : acceleration)
            : @"—", left, portrait ? 565 : 275, portrait ? 28 : 23, GREEN, LEFT);
    label(c, @"m/s²", portrait ? 119 : 105, portrait ? 565 : 275, 13, SOFT, LEFT);

    CLLocation *fix = self.telemetry.fix;
    label(c, @"GNSS POSITION", right, portrait ? 526 : 228, 12, SOFT, RIGHT);
    label(c, fix == nil ? @"LAT  —" : fmt(@"LAT  %+.5f°", fix.coordinate.latitude),
            right, portrait ? 547 : 250, 15, GREEN, RIGHT);
    label(c, fix == nil ? @"LON  —" : fmt(@"LON  %+.5f°", fix.coordinate.longitude),
            right, portrait ? 569 : 273, 15, GREEN, RIGHT);
    CGContextRestoreGState(c);
}
@end
