// JNI side: the Engine behind app.compendium.android.Native, and an HttpClient that calls back into
// Kotlin's Bridge (Android's own HTTPS stack and certificate store, so no libcurl or CA bundle).
// Strings cross as UTF-8 byte arrays: JNI's own string functions use "modified UTF-8", which
// breaks on emoji in mod names.
#include <jni.h>

#include <memory>
#include <stdexcept>
#include <string>

#include "engine.hpp"

namespace {

JavaVM* g_vm = nullptr;
jclass g_bridge = nullptr;
jclass g_httpStatusEx = nullptr;
jmethodID g_httpGet, g_httpDownload, g_httpHead, g_httpRange, g_onProgress, g_exCode;
jmethodID g_throwableMessage;
thread_local JNIEnv* t_env = nullptr;  // the calling Java thread's environment, set by Native.call

std::string fromJ(JNIEnv* env, jbyteArray a) {
    if (!a) return {};
    jsize n = env->GetArrayLength(a);
    std::string s(static_cast<size_t>(n), '\0');
    if (n) env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(s.data()));
    return s;
}

jbyteArray toJ(JNIEnv* env, const std::string& s) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(s.size()));
    if (a && !s.empty()) env->SetByteArrayRegion(a, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
    return a;
}

// Turn a pending Java exception into a C++ one.
void rethrow(JNIEnv* env) {
    if (!env->ExceptionCheck()) return;
    jthrowable ex = env->ExceptionOccurred();
    env->ExceptionClear();
    if (env->IsInstanceOf(ex, g_httpStatusEx)) {
        long code = env->CallIntMethod(ex, g_exCode);
        env->DeleteLocalRef(ex);
        throw mm::HttpStatusError(code);
    }
    std::string msg = "network error";
    jstring m = static_cast<jstring>(env->CallObjectMethod(ex, g_throwableMessage));
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (m) {
        const char* c = env->GetStringUTFChars(m, nullptr);
        if (c) msg = c;
        env->ReleaseStringUTFChars(m, c);
        env->DeleteLocalRef(m);
    }
    env->DeleteLocalRef(ex);
    throw std::runtime_error(msg);
}

class JniHttpClient : public mm::HttpClient {
public:
    std::string get(const std::string& url) override {
        JNIEnv* env = t_env;
        jbyteArray u = toJ(env, url);
        auto r = static_cast<jbyteArray>(env->CallStaticObjectMethod(g_bridge, g_httpGet, u));
        env->DeleteLocalRef(u);
        rethrow(env);
        auto s = fromJ(env, r);
        if (r) env->DeleteLocalRef(r);
        return s;
    }
    void download(const std::string& url, const mm::fs::path& dest, const mm::DownloadProgressFn& progress) override {
        JNIEnv* env = t_env;
        jbyteArray u = toJ(env, url);
        jbyteArray d = toJ(env, dest.string());
        env->CallStaticVoidMethod(g_bridge, g_httpDownload, u, d, reinterpret_cast<jlong>(&progress));
        env->DeleteLocalRef(u);
        env->DeleteLocalRef(d);
        rethrow(env);
    }
    int64_t contentLength(const std::string& url) override {
        JNIEnv* env = t_env;
        jbyteArray u = toJ(env, url);
        jlong n = env->CallStaticLongMethod(g_bridge, g_httpHead, u);
        env->DeleteLocalRef(u);
        rethrow(env);
        return n;
    }
    std::string getRange(const std::string& url, uint64_t from, uint64_t to, std::string*) override {
        JNIEnv* env = t_env;
        jbyteArray u = toJ(env, url);
        auto r = static_cast<jbyteArray>(env->CallStaticObjectMethod(g_bridge, g_httpRange, u, static_cast<jlong>(from), static_cast<jlong>(to)));
        env->DeleteLocalRef(u);
        rethrow(env);
        auto s = fromJ(env, r);
        if (r) env->DeleteLocalRef(r);
        return s;
    }
};

std::unique_ptr<android::Engine> g_engine;

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass bridge = env->FindClass("app/compendium/android/Bridge");
    jclass ex = env->FindClass("app/compendium/android/HttpStatusException");
    jclass throwable = env->FindClass("java/lang/Throwable");
    if (!bridge || !ex || !throwable) return JNI_ERR;
    g_bridge = static_cast<jclass>(env->NewGlobalRef(bridge));
    g_httpStatusEx = static_cast<jclass>(env->NewGlobalRef(ex));
    g_httpGet = env->GetStaticMethodID(bridge, "httpGet", "([B)[B");
    g_httpDownload = env->GetStaticMethodID(bridge, "httpDownload", "([B[BJ)V");
    g_httpHead = env->GetStaticMethodID(bridge, "httpHead", "([B)J");
    g_httpRange = env->GetStaticMethodID(bridge, "httpRange", "([BJJ)[B");
    g_onProgress = env->GetStaticMethodID(bridge, "onProgress", "([B[BF)V");
    g_exCode = env->GetMethodID(ex, "getCode", "()I");
    g_throwableMessage = env->GetMethodID(throwable, "getMessage", "()Ljava/lang/String;");
    if (!g_httpGet || !g_httpDownload || !g_httpHead || !g_httpRange || !g_onProgress || !g_exCode || !g_throwableMessage) return JNI_ERR;
    g_engine = std::make_unique<android::Engine>(
        std::make_unique<JniHttpClient>(), [](const std::string& task, const std::string& text, float pct) {
            JNIEnv* e = t_env;
            if (!e) return;
            jbyteArray t = toJ(e, task);
            jbyteArray x = toJ(e, text);
            e->CallStaticVoidMethod(g_bridge, g_onProgress, t, x, pct);
            if (e->ExceptionCheck()) e->ExceptionClear();
            e->DeleteLocalRef(t);
            e->DeleteLocalRef(x);
        });
    return JNI_VERSION_1_6;
}

// Native.call(method: ByteArray, args: ByteArray): ByteArray
JNIEXPORT jbyteArray JNICALL Java_app_compendium_android_Native_call(JNIEnv* env, jclass, jbyteArray method, jbyteArray args) {
    t_env = env;
    std::string out = g_engine->call(fromJ(env, method), fromJ(env, args));
    t_env = nullptr;
    return toJ(env, out);
}

JNIEXPORT void JNICALL Java_app_compendium_android_Native_cancel(JNIEnv* env, jclass, jbyteArray task) {
    g_engine->cancel(fromJ(env, task));
}

// Native.progress(handle, done, total): called by Kotlin's download loop; false = stop.
JNIEXPORT jboolean JNICALL Java_app_compendium_android_Native_progress(JNIEnv*, jclass, jlong handle, jlong done, jlong total) {
    auto* fn = reinterpret_cast<const mm::DownloadProgressFn*>(handle);
    if (!fn || !*fn) return JNI_TRUE;
    return (*fn)(static_cast<uint64_t>(done), static_cast<uint64_t>(total)) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
