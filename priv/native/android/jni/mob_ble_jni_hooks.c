// JNI load hook for the mob_ble Android plugin.
// The host-generated plugin dispatcher calls this during JNI_OnLoad.

#include <jni.h>

void mob_ble_cache_class(JNIEnv *env);

void mob_ble_jni_on_load(JNIEnv *env) {
    mob_ble_cache_class(env);
}
