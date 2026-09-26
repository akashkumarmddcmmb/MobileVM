#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "native_vm_engine.h"
#include "native_arch.h"
#include "native_cpu_kvm.h"
#include "native_disk.h"

#define LOG_TAG "MobileVM-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeCreateVM(
    JNIEnv* env,
    jobject /* this */,
    jint ramMb,
    jstring diskPath,
    jint numCores,
    jint guestArchCode,
    jboolean useHardwareVirt,
    jstring kernelPath,
    jstring initramfsPath,
    jstring cmdline,
    jstring consoleDev) {
    
    auto extractString = [env](jstring jStr) -> std::string {
        if (!jStr) return "";
        const char* chars = env->GetStringUTFChars(jStr, nullptr);
        std::string str(chars ? chars : "");
        if (chars) env->ReleaseStringUTFChars(jStr, chars);
        return str;
    };

    std::string strDiskPath = extractString(diskPath);
    std::string strKernelPath = extractString(kernelPath);
    std::string strInitramfsPath = extractString(initramfsPath);
    std::string strCmdline = extractString(cmdline);
    std::string strConsoleDev = extractString(consoleDev);

    GuestArchitecture gArch = static_cast<GuestArchitecture>(guestArchCode);

    NativeVMEngine* engine = new NativeVMEngine(
        static_cast<size_t>(ramMb),
        strDiskPath,
        numCores,
        gArch,
        useHardwareVirt == JNI_TRUE,
        strKernelPath,
        strInitramfsPath,
        strCmdline,
        strConsoleDev
    );

    LOGI("Native VM Engine created with %d MB RAM, guest arch: %d, virt: %d, kernel: %s at %p",
         ramMb, guestArchCode, useHardwareVirt, strKernelPath.c_str(), engine);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jstring JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeVerifyKernelImage(
    JNIEnv* env,
    jobject /* this */,
    jstring kernelPath) {
    const char* cPath = env->GetStringUTFChars(kernelPath, nullptr);
    if (!cPath) return env->NewStringUTF("Kernel path is null");
    std::string path(cPath);
    env->ReleaseStringUTFChars(kernelPath, cPath);

    Arm64KernelHeader hdr;
    std::string err;
    if (NativeLinuxBootLoader::verifyKernelImage(path, hdr, err)) {
        return env->NewStringUTF("VALID_ARM64_IMAGE");
    }
    return env->NewStringUTF(err.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeConfigure(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->configure() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeStart(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativePause(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->pause() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeResume(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->resume() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeStop(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->stop() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeReset(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->reset() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeDestroy(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (engine) {
        delete engine;
        LOGI("Native VM Engine destroyed");
    }
}

JNIEXPORT jint JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeStepCycles(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jint maxCycles) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return 0;
    return engine->stepCycles(maxCycles);
}

JNIEXPORT jint JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeGetState(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return static_cast<jint>(VMNativeState::ERROR);
    return static_cast<jint>(engine->getState());
}

JNIEXPORT jlongArray JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeGetRegisters(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return nullptr;

    jlongArray result = env->NewLongArray(34);
    if (!result) return nullptr;

    const auto& regs = engine->getCPU().getRegisters();
    jlong temp[34];
    for (int i = 0; i < 32; i++) {
        temp[i] = static_cast<jlong>(regs[i]);
    }
    temp[32] = static_cast<jlong>(engine->getCPU().getPC());
    temp[33] = static_cast<jlong>(engine->getCPU().getSP());

    env->SetLongArrayRegion(result, 0, 34, temp);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeFetchSerialTx(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return nullptr;

    std::vector<uint8_t> tx = engine->fetchSerialTx();
    if (tx.empty()) return nullptr;

    jbyteArray result = env->NewByteArray(tx.size());
    if (result) {
        env->SetByteArrayRegion(result, 0, tx.size(), reinterpret_cast<const jbyte*>(tx.data()));
    }
    return result;
}

JNIEXPORT void JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeWriteSerialRx(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jbyte rxByte) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (engine) {
        engine->writeSerialRx(static_cast<uint8_t>(rxByte));
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeCopyFramebuffer(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jintArray outPixels) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine || !outPixels) return JNI_FALSE;

    const uint32_t* fb = engine->getFramebuffer();
    if (!fb) return JNI_FALSE;

    jsize len = env->GetArrayLength(outPixels);
    env->SetIntArrayRegion(outPixels, 0, len, reinterpret_cast<const jint*>(fb));
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeIsKvmSupported(
    JNIEnv* env,
    jobject /* this */) {
    return NativeCPUKVM::isAvailableOnHost() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeGetKvmReason(
    JNIEnv* env,
    jobject /* this */) {
    std::string reason = NativeCPUKVM::getAvailabilityReason();
    return env->NewStringUTF(reason.c_str());
}

JNIEXPORT jint JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeGetHostArchitecture(
    JNIEnv* env,
    jobject /* this */) {
    return static_cast<jint>(NativeArchDetector::detectHostArchitecture());
}

JNIEXPORT jboolean JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeIsFallbackEmulation(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->isFallbackEmulation() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeGetBackendStatus(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return env->NewStringUTF("No active VM handle");
    return env->NewStringUTF(engine->getBackendStatus().c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeIsHardwareAccelerated(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return JNI_FALSE;
    return engine->isHardwareAccelerated() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeGetBackendDescription(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return env->NewStringUTF("Inactive Engine");
    return env->NewStringUTF(engine->getBackendDescription().c_str());
}

// --- Virtual Disk Native Operations ---

JNIEXPORT jboolean JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeCreateDiskImage(
    JNIEnv* env,
    jobject /* this */,
    jstring diskPath,
    jlong sizeBytes,
    jboolean sparse) {
    const char* cPath = env->GetStringUTFChars(diskPath, nullptr);
    if (!cPath) return JNI_FALSE;

    std::string path(cPath);
    env->ReleaseStringUTFChars(diskPath, cPath);

    std::string error;
    bool ok = NativeDisk::createRawDisk(path, static_cast<uint64_t>(sizeBytes), sparse == JNI_TRUE, error);
    if (!ok) {
        LOGE("Failed to create raw virtual disk %s: %s", path.c_str(), error.c_str());
    } else {
        LOGI("Created raw virtual disk %s (%lld bytes, sparse=%d)", path.c_str(), (long long)sizeBytes, sparse);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeGetDiskSectorCount(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return 0;
    return static_cast<jlong>(engine->getDevices().getDisk().getSectorCount());
}

JNIEXPORT jlong JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeGetDiskReadSectors(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return 0;
    return static_cast<jlong>(engine->getDevices().getDisk().getTotalSectorsRead());
}

JNIEXPORT jlong JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeGetDiskWrittenSectors(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return 0;
    return static_cast<jlong>(engine->getDevices().getDisk().getTotalSectorsWritten());
}

JNIEXPORT jbyteArray JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeReadSectorBytes(
    JNIEnv* env,
    jobject /* this */,
    jstring diskPath,
    jlong lba,
    jint count) {
    const char* cPath = env->GetStringUTFChars(diskPath, nullptr);
    if (!cPath) return nullptr;
    std::string path(cPath);
    env->ReleaseStringUTFChars(diskPath, cPath);

    NativeDisk disk;
    std::string err;
    if (!disk.openRawDisk(path, true, "", err)) {
        return nullptr;
    }

    size_t totalBytes = count * 512;
    std::vector<uint8_t> buffer(totalBytes, 0);
    if (!disk.readSectors(static_cast<uint64_t>(lba), static_cast<uint32_t>(count), buffer.data(), err)) {
        return nullptr;
    }

    jbyteArray result = env->NewByteArray(totalBytes);
    if (result) {
        env->SetByteArrayRegion(result, 0, totalBytes, reinterpret_cast<const jbyte*>(buffer.data()));
    }
    return result;
}

// --- Virtual Input Device JNI Bindings ---

JNIEXPORT void JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeSendTouchEvent(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jint action,
    jfloat x,
    jfloat y,
    jfloat pressure,
    jint pointerId) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (engine) {
        engine->getDevices().getInput().postTouchEvent(action, x, y, pressure, pointerId);
    }
}

JNIEXPORT void JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeSendMouseEvent(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jint buttonMask,
    jint dx,
    jint dy,
    jint absX,
    jint absY,
    jint wheelDelta) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (engine) {
        engine->getDevices().getInput().postMouseEvent(buttonMask, dx, dy, absX, absY, wheelDelta);
    }
}

JNIEXPORT void JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeSendKeyEvent(
    JNIEnv* env,
    jobject /* this */,
    jlong handle,
    jint scanCode,
    jboolean isDown) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (engine) {
        engine->getDevices().getInput().postKeyEvent(static_cast<uint16_t>(scanCode), isDown == JNI_TRUE);
    }
}

JNIEXPORT jlong JNICALL
Java_com_example_vm_nativebridge_NativeVMBinding_nativeGetInputEventCount(
    JNIEnv* env,
    jobject /* this */,
    jlong handle) {
    NativeVMEngine* engine = reinterpret_cast<NativeVMEngine*>(handle);
    if (!engine) return 0;
    return static_cast<jlong>(engine->getDevices().getInput().getTotalEventsProcessed());
}

} // extern "C"
