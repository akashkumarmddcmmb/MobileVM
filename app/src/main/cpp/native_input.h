#ifndef NATIVE_INPUT_H
#define NATIVE_INPUT_H

#include <cstdint>
#include <vector>
#include <mutex>
#include <queue>

// Standard Linux input_event protocol (linux/input.h / evdev)
struct LinuxInputEvent {
    uint64_t timeSec;
    uint64_t timeUsec;
    uint16_t type;
    uint16_t code;
    int32_t value;
};

// Event Types
constexpr uint16_t EV_SYN = 0x00;
constexpr uint16_t EV_KEY = 0x01;
constexpr uint16_t EV_REL = 0x02;
constexpr uint16_t EV_ABS = 0x03;

// Synchronization Codes
constexpr uint16_t SYN_REPORT = 0x00;

// Key / Button Codes
constexpr uint16_t BTN_TOUCH  = 0x14A;
constexpr uint16_t BTN_LEFT   = 0x110;
constexpr uint16_t BTN_RIGHT  = 0x111;
constexpr uint16_t BTN_MIDDLE = 0x112;

// Absolute Axis Codes
constexpr uint16_t ABS_X = 0x00;
constexpr uint16_t ABS_Y = 0x01;
constexpr uint16_t ABS_PRESSURE = 0x18;
constexpr uint16_t ABS_MT_SLOT = 0x2F;
constexpr uint16_t ABS_MT_TOUCH_MAJOR = 0x30;
constexpr uint16_t ABS_MT_POSITION_X = 0x35;
constexpr uint16_t ABS_MT_POSITION_Y = 0x36;
constexpr uint16_t ABS_MT_TRACKING_ID = 0x39;

// Relative Axis Codes
constexpr uint16_t REL_X = 0x00;
constexpr uint16_t REL_Y = 0x01;
constexpr uint16_t REL_WHEEL = 0x08;

class NativeInputDevice {
public:
    NativeInputDevice(uint32_t screenWidth = 1024, uint32_t screenHeight = 768);
    ~NativeInputDevice();

    void postTouchEvent(int action, float x, float y, float pressure, int pointerId);
    void postMouseEvent(int buttonMask, int dx, int dy, int absX = -1, int absY = -1, int wheelDelta = 0);
    void postKeyEvent(uint16_t scanCode, bool isDown);

    bool hasPendingEvents();
    bool popEvent(LinuxInputEvent& outEvent);
    void clear();

    uint32_t getAbsMaxX() const { return maxX; }
    uint32_t getAbsMaxY() const { return maxY; }

    // MMIO read/write handlers for VirtIO Input
    uint32_t handleMMIORead(uint64_t offset);
    void handleMMIOWrite(uint64_t offset, uint32_t value);

    // Stats
    uint64_t getTotalEventsProcessed() const { return totalEventsCount; }

private:
    uint32_t maxX;
    uint32_t maxY;
    std::queue<LinuxInputEvent> eventQueue;
    mutable std::mutex inputMutex;
    uint64_t totalEventsCount;

    void enqueueEvent(uint16_t type, uint16_t code, int32_t value);
    void enqueueSync();
};

#endif // NATIVE_INPUT_H
