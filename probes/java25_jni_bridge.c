#include <jni.h>
#include <android/log.h>

int android_compile_project(int argc, char **argv);

JNIEXPORT jint JNICALL Java_gdcc_probe_Java25TccProbe_compile(
        JNIEnv *env, jclass klass, jstring bundle, jstring fixture,
        jstring output, jstring entry) {
    (void)klass;
    const char *bundle_path = (*env)->GetStringUTFChars(env, bundle, NULL);
    const char *fixture_path = (*env)->GetStringUTFChars(env, fixture, NULL);
    const char *output_path = (*env)->GetStringUTFChars(env, output, NULL);
    const char *entry_path = (*env)->GetStringUTFChars(env, entry, NULL);
    if (!bundle_path || !fixture_path || !output_path || !entry_path) {
        __android_log_write(ANDROID_LOG_ERROR, "godot", "JAVA25_JNI_STRING_FAIL");
        if (bundle_path) (*env)->ReleaseStringUTFChars(env, bundle, bundle_path);
        if (fixture_path) (*env)->ReleaseStringUTFChars(env, fixture, fixture_path);
        if (output_path) (*env)->ReleaseStringUTFChars(env, output, output_path);
        if (entry_path) (*env)->ReleaseStringUTFChars(env, entry, entry_path);
        return 1;
    }

    char *args[] = {"compile", (char *)bundle_path, (char *)fixture_path,
        (char *)output_path, (char *)entry_path};
    int result = android_compile_project(5, args);
    __android_log_print(ANDROID_LOG_INFO, "godot", "JAVA25_JNI_TCC_RESULT::%d", result);

    (*env)->ReleaseStringUTFChars(env, bundle, bundle_path);
    (*env)->ReleaseStringUTFChars(env, fixture, fixture_path);
    (*env)->ReleaseStringUTFChars(env, output, output_path);
    (*env)->ReleaseStringUTFChars(env, entry, entry_path);
    return result;
}
