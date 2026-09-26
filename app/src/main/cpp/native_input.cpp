#include "native_input.h"
#include <chrono>

NativeInputDevice::NativeInputDevice(uint32_t screenWidth, uint32_t screenHeight)
    : maxX(screenWidth), maxY(screenHeight), totalEventsCount(0) {}

NativeInputDevice::~NativeInputDevice() {
    clear();
}

void NativeInputDevice::enqueueEvent(uint16_t type, uint16_t code, int32_t value) {
    auto now = std::chrono::system_clock::now();
    auto epoch = now.time_since_epoch();
    auto sec = std::chrono::duration_cast<std::chrono::seconds>(epoch).count();
    auto usec = std::chrono::duration_cast<std::chrono::microseconds>(epoch).count() % 1000000;

    LinuxInputEvent ev;
    ev.timeSec = static_cast<uint64_t>(sec);
    ev.timeUsec = static_cast<uint64_t>(usec);
    ev.type = type;
    ev.code = code;
    ev.value = value;

    eventQueue.push(ev);
    totalEventsCount++;
}

void NativeInputDevice::enqueueSync() {
    enqueueEvent(EV_SYN, SYN_REPORT, 0);
}

void NativeInputDevice::postTouchEvent(int action, float x, float y, float pressure, int pointerId) {
    std::lock_guard<std::mutex> lock(inputMutex);

    int32_t clampedX = static_cast<int32_t>(x < 0 ? 0 : (x > maxX ? maxX : x));
    int32_t clampedY = static_cast<int32_t>(y < 0 ? 0 : (y > maxY ? maxY : y));

    switch (action) {
        case 0: // Touch Down
            enqueueEvent(EV_ABS, ABS_MT_SLOT, pointerId);
            enqueueEvent(EV_ABS, ABS_MT_TRACKING_ID, pointerId);
            enqueueEvent(EV_ABS, ABS_MT_POSITION_X, clampedX);
            enqueueEvent(EV_ABS, ABS_MT_POSITION_Y, clampedY);
            enqueueEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, static_cast<int32_t>(pressure * 100.0f));
            enqueueEvent(EV_ABS, ABS_X, clampedX);
            enqueueEvent(EV_ABS, ABS_Y, clampedY);
            enqueueEvent(EV_ABS, ABS_PRESSURE, static_cast<int32_t>(pressure * 255.0f));
            enqueueEvent(EV_KEY, BTN_TOUCH, 1);
            enqueueSync();
            break;

        case 2: // Touch Move
            enqueueEvent(EV_ABS, ABS_MT_SLOT, pointerId);
            enqueueEvent(EV_ABS, ABS_MT_POSITION_X, clampedX);
            enqueueEvent(EV_ABS, ABS_MT_POSITION_Y, clampedY);
            enqueueEvent(EV_ABS, ABS_MT_TOUCH_MAJOR, static_cast<int32_t>(pressure * 100.0f));
            enqueueEvent(EV_ABS, ABS_X, clampedX);
            enqueueEvent(EV_ABS, ABS_Y, clampedY);
            enqueueSync();
            break;

        case 1: // Touch Up
        case 3: // Touch Cancel
            enqueueEvent(EV_ABS, ABS_MT_SLOT, pointerId);
            enqueueEvent(EV_ABS, ABS_MT_TRACKING_ID, -1);
            enqueueEvent(EV_KEY, BTN_TOUCH, 0);
            enqueueSync();
            break;

        default:
            break;
    }
}

void NativeInputDevice::postMouseEvent(int buttonMask, int dx, int dy, int absX, int absY, int wheelDelta) {
    std::lock_guard<std::mutex> lock(inputMutex);

    if (dx != 0 || dy != 0) {
        if (dx != 0) enqueueEvent(EV_REL, REL_X, dx);
        if (dy != 0) enqueueEvent(EV_REL, REL_Y, dy);
    }
    if (wheelDelta != 0) {
        enqueueEvent(EV_REL, REL_WHEEL, wheelDelta);
    }
    if (absX >= 0 && absY >= 0) {
        enqueueEvent(EV_ABS, ABS_X, absX);
        enqueueEvent(EV_ABS, ABS_Y, absY);
    }

    enqueueEvent(EV_KEY, BTN_LEFT, (buttonMask & 1) ? 1 : 0);
    enqueueEvent(EV_KEY, BTN_RIGHT, (buttonMask & 2) ? 1 : 0);
    enqueueEvent(EV_KEY, BTN_MIDDLE, (buttonMask & 4) ? 1 : 0);
    enqueueSync();
}

void NativeInputDevice::postKeyEvent(uint16_t scanCode, bool isDown) {
    std::lock_guard<std::mutex> lock(inputMutex);
    enqueueEvent(EV_KEY, scanCode, isDown ? 1 : 0);
    enqueueSync();
}

bool NativeInputDevice::hasPendingEvents() {
    std::lock_guard<std::mutex> lock(inputMutex);
    return !eventQueue.empty();
}

bool NativeInputDevice::popEvent(LinuxInputEvent& outEvent) {
    std::lock_guard<std::mutex> lock(inputMutex);
    if (eventQueue.empty()) return false;
    outEvent = eventQueue.front();
    eventQueue.pop();
    return true;
}

void NativeInputDevice::clear() {
    std::lock_guard<std::mutex> lock(inputMutex);
    while (!eventQueue.empty()) {
        eventQueue.pop();
    }
}

uint32_t NativeInputDevice::handleMMIORead(uint64_t offset) {
    std::lock_guard<std::mutex> lock(inputMutex);
    switch (offset) {
        case 0x00: return eventQueue.empty() ? 0 : 1; // Event available flag
        case 0x04: return static_cast<uint32_t>(eventQueue.size());
        case 0x08: return maxX;
        case 0x0C: return maxY;
        case 0x10: { // Read and pop event word 0 (type & code)
            if (eventQueue.empty()) return 0;
            const auto& ev = eventQueue.front();
            return (static_cast<uint32_t>(ev.type) << 16) | ev.code;
        }
        case 0x14: { // Read and pop event word 1 (value)
            if (eventQueue.empty()) return 0;
            auto ev = eventQueue.front();
            eventQueue.pop();
            return static_cast<uint32_t>(ev.value);
        }
        default:
            return 0;
    }
}

void NativeInputDevice::handleMMIOWrite(uint64_t offset, uint32_t value) {
    std::lock_guard<std::mutex> lock(inputMutex);
    if (offset == 0x00 && value == 0xFFFFFFFF) {
        while (!eventQueue.empty()) eventQueue.pop();
    }
}
