// mob_ble_nif.c — Android BLE NIF for the mob_ble plugin.
//
// Android counterpart of the iOS mob_ble_nif.m. The Erlang/Elixir surface
// (start_scan/1, start_advertising/2, stop/1, send_ping/3) is identical
// across platforms — the difference lives entirely below the NIF:
//
//   * iOS    — the NIF calls statically-linked Swift via `extern` C
//              functions and Swift calls back through mob_ble_emit_*.
//   * Android — the NIF calls the Kotlin object `MobBleNative` over
//              JNI, and Kotlin's event sink calls back through the
//              JNI-exported nativeDeliverEvent below.
//
// The NIF is loadable as a static NIF for the mob plugin via the
// upstreamed mechanism (ERL_NIF_INIT(mob_ble_nif, ...) produces
// mob_ble_nif_nif_init for driver tab registration).
//
// Event delivery contract: events reach the registered owner pid as
//   {Elixir.Mob.Ble.MobileBridge, :bridge_event, <json-binary>}
// where <json-binary> is the v1 wire-format JSON produced by the
// platform BLE event encoder. The MobileBridge (or NIF loader) decodes it.

#include "erl_nif.h"
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "MobBleNif"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// g_jvm is defined by the generated Android plugin runtime and set from
// JNI_OnLoad before any static NIF calls.
extern JavaVM *g_jvm;

// ── Cached JNI handles ───────────────────────────────────────────────────────
// Resolved once via mob_ble_cache_class() (called by the generated plugin
// dispatcher during JNI_OnLoad), where the app classloader is in scope
// (FindClass on a bare
// AttachCurrentThread'd scheduler thread would only see the system
// classloader and miss app classes).
static jclass    g_ble_class       = NULL;
static jmethodID g_mid_start_scan  = NULL;  // static boolean startScan()
static jmethodID g_mid_start_adv   = NULL;  // static boolean startAdvertising(String)
static jmethodID g_mid_stop        = NULL;  // static boolean stop()
static jmethodID g_mid_send        = NULL;  // static boolean sendToPeer(String, byte[])

// ── Owner pid ────────────────────────────────────────────────────────────────
static ErlNifMutex *owner_mutex = NULL;
static ErlNifPid    owner_pid;
static int          owner_set = 0;

static ERL_NIF_TERM atom(ErlNifEnv *env, const char *name) {
    return enif_make_atom(env, name);
}

static ERL_NIF_TERM ok(ErlNifEnv *env) {
    return atom(env, "ok");
}

static ERL_NIF_TERM error_tuple(ErlNifEnv *env, const char *reason) {
    return enif_make_tuple2(env, atom(env, "error"), atom(env, reason));
}

static int set_owner(ErlNifEnv *env, ERL_NIF_TERM owner) {
    ErlNifPid pid;
    if (!enif_get_local_pid(env, owner, &pid)) {
        return 0;
    }
    enif_mutex_lock(owner_mutex);
    owner_pid = pid;
    owner_set = 1;
    enif_mutex_unlock(owner_mutex);
    return 1;
}

static char *copy_string_arg(ErlNifEnv *env, ERL_NIF_TERM term) {
    ErlNifBinary bin;
    if (!enif_inspect_iolist_as_binary(env, term, &bin)) {
        return NULL;
    }
    char *copy = (char *)enif_alloc(bin.size + 1);
    if (copy == NULL) {
        return NULL;
    }
    memcpy(copy, bin.data, bin.size);
    copy[bin.size] = '\0';
    return copy;
}

// ── JNI thread attachment ────────────────────────────────────────────────────
// Returns 0 already-attached, 1 attached-by-us (caller detaches), -1 failed.
static int get_jni_env(JNIEnv **env) {
    jint st = (*g_jvm)->GetEnv(g_jvm, (void **)env, JNI_VERSION_1_6);
    if (st == JNI_OK) {
        return 0;
    }
    if (st == JNI_EDETACHED) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, env, NULL) == JNI_OK) {
            return 1;
        }
    }
    *env = NULL;
    return -1;
}

// Calls a cached static boolean method (no args, or one String arg, or
// String + byte[] — selected by the methodID passed). Returns the
// jboolean result, or JNI_FALSE on any JNI failure.
static int call_static_bool0(jmethodID mid) {
    if (g_ble_class == NULL || mid == NULL) {
        LOGE("call_static_bool0: MobBleNative not cached");
        return 0;
    }
    JNIEnv *jenv = NULL;
    int attached = get_jni_env(&jenv);
    if (attached < 0) {
        LOGE("call_static_bool0: AttachCurrentThread failed");
        return 0;
    }
    jboolean res = (*jenv)->CallStaticBooleanMethod(jenv, g_ble_class, mid);
    if ((*jenv)->ExceptionCheck(jenv)) {
        (*jenv)->ExceptionClear(jenv);
        res = JNI_FALSE;
    }
    if (attached == 1) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
    return res ? 1 : 0;
}

static int call_start_advertising(const char *local_name) {
    if (g_ble_class == NULL || g_mid_start_adv == NULL) {
        return 0;
    }
    JNIEnv *jenv = NULL;
    int attached = get_jni_env(&jenv);
    if (attached < 0) {
        return 0;
    }
    jstring jname = (*jenv)->NewStringUTF(jenv, local_name ? local_name : "mob-ble");
    jboolean res = (*jenv)->CallStaticBooleanMethod(jenv, g_ble_class, g_mid_start_adv, jname);
    if ((*jenv)->ExceptionCheck(jenv)) {
        (*jenv)->ExceptionClear(jenv);
        res = JNI_FALSE;
    }
    if (jname) {
        (*jenv)->DeleteLocalRef(jenv, jname);
    }
    if (attached == 1) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
    return res ? 1 : 0;
}

static int call_send_to_peer(const char *peer_id, const uint8_t *payload, size_t len) {
    if (g_ble_class == NULL || g_mid_send == NULL) {
        return 0;
    }
    JNIEnv *jenv = NULL;
    int attached = get_jni_env(&jenv);
    if (attached < 0) {
        return 0;
    }
    jstring jpeer = (*jenv)->NewStringUTF(jenv, peer_id ? peer_id : "");
    jbyteArray jpayload = (*jenv)->NewByteArray(jenv, (jsize)len);
    if (jpayload && len > 0 && payload) {
        (*jenv)->SetByteArrayRegion(jenv, jpayload, 0, (jsize)len, (const jbyte *)payload);
    }
    jboolean res = (*jenv)->CallStaticBooleanMethod(jenv, g_ble_class, g_mid_send, jpeer, jpayload);
    if ((*jenv)->ExceptionCheck(jenv)) {
        (*jenv)->ExceptionClear(jenv);
        res = JNI_FALSE;
    }
    if (jpeer) (*jenv)->DeleteLocalRef(jenv, jpeer);
    if (jpayload) (*jenv)->DeleteLocalRef(jenv, jpayload);
    if (attached == 1) {
        (*g_jvm)->DetachCurrentThread(g_jvm);
    }
    return res ? 1 : 0;
}

// ── Class caching (called from generated JNI_OnLoad dispatcher) ──────────────
void mob_ble_cache_class(JNIEnv *env) {
    jclass local = (*env)->FindClass(env, "mob/ble/MobBleNative");
    if (local == NULL) {
        (*env)->ExceptionClear(env);
        LOGE("mob_ble_cache_class: FindClass mob/ble/MobBleNative failed");
        return;
    }
    g_ble_class = (jclass)(*env)->NewGlobalRef(env, local);
    (*env)->DeleteLocalRef(env, local);

    g_mid_start_scan = (*env)->GetStaticMethodID(env, g_ble_class, "startScan", "()Z");
    g_mid_start_adv  = (*env)->GetStaticMethodID(env, g_ble_class, "startAdvertising",
                                                 "(Ljava/lang/String;)Z");
    g_mid_stop       = (*env)->GetStaticMethodID(env, g_ble_class, "stop", "()Z");
    g_mid_send       = (*env)->GetStaticMethodID(env, g_ble_class, "sendToPeer",
                                                 "(Ljava/lang/String;[B)Z");
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        LOGE("mob_ble_cache_class: GetStaticMethodID failed");
        return;
    }
    LOGI("mob_ble_cache_class: MobBleNative cached");
}

// ── JNI -> BEAM event delivery ───────────────────────────────────────────────
// Called from the Kotlin event sink (a binder/callback thread that is
// already attached to the JVM). Wraps the v1-wire JSON in the bridge
// event envelope and sends it to the owner pid (typically the MobileBridge).
JNIEXPORT void JNICALL
Java_mob_ble_MobBleNative_nativeDeliverEvent(JNIEnv *env, jclass cls, jstring json) {
    (void)cls;
    if (json == NULL) {
        return;
    }
    enif_mutex_lock(owner_mutex);
    int have = owner_set;
    ErlNifPid pid = owner_pid;
    enif_mutex_unlock(owner_mutex);
    if (!have) {
        return;
    }

    const char *cjson = (*env)->GetStringUTFChars(env, json, NULL);
    if (cjson == NULL) {
        return;
    }
    size_t len = strlen(cjson);

    ErlNifEnv *msg_env = enif_alloc_env();
    ERL_NIF_TERM json_bin;
    unsigned char *buf = enif_make_new_binary(msg_env, len, &json_bin);
    memcpy(buf, cjson, len);
    (*env)->ReleaseStringUTFChars(env, json, cjson);

    ERL_NIF_TERM msg = enif_make_tuple3(
        msg_env,
        atom(msg_env, "Elixir.Mob.Ble.MobileBridge"),
        atom(msg_env, "bridge_event"),
        json_bin);

    enif_send(NULL, &pid, msg_env, msg);
    enif_free_env(msg_env);
}

// ── NIF functions ────────────────────────────────────────────────────────────
static ERL_NIF_TERM nif_start_scan(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
    if (argc != 1 || !set_owner(env, argv[0])) {
        return enif_make_badarg(env);
    }
    return call_static_bool0(g_mid_start_scan) ? ok(env) : error_tuple(env, "start_scan_rejected");
}

static ERL_NIF_TERM nif_start_advertising(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
    if (argc != 2 || !set_owner(env, argv[0])) {
        return enif_make_badarg(env);
    }
    char *local_name = copy_string_arg(env, argv[1]);
    if (local_name == NULL) {
        return enif_make_badarg(env);
    }
    int accepted = call_start_advertising(local_name);
    enif_free(local_name);
    return accepted ? ok(env) : error_tuple(env, "start_advertising_rejected");
}

static ERL_NIF_TERM nif_stop(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
    if (argc != 1 || !set_owner(env, argv[0])) {
        return enif_make_badarg(env);
    }
    return call_static_bool0(g_mid_stop) ? ok(env) : error_tuple(env, "stop_rejected");
}

static ERL_NIF_TERM nif_send_ping(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
    if (argc != 3 || !set_owner(env, argv[0])) {
        return enif_make_badarg(env);
    }
    char *peer_id = copy_string_arg(env, argv[1]);
    if (peer_id == NULL) {
        return enif_make_badarg(env);
    }
    ErlNifBinary payload;
    if (!enif_inspect_iolist_as_binary(env, argv[2], &payload)) {
        enif_free(peer_id);
        return enif_make_badarg(env);
    }
    int accepted = call_send_to_peer(peer_id, payload.data, payload.size);
    enif_free(peer_id);
    return accepted ? ok(env) : error_tuple(env, "send_rejected");
}

static ErlNifFunc nif_funcs[] = {
    {"start_scan", 1, nif_start_scan, 0},
    {"start_advertising", 2, nif_start_advertising, 0},
    {"stop", 1, nif_stop, 0},
    {"send_ping", 3, nif_send_ping, 0}
};

static int nif_load(ErlNifEnv *env, void **priv, ERL_NIF_TERM info) {
    (void)env;
    (void)priv;
    (void)info;
    owner_mutex = enif_mutex_create("mob_ble_owner_mutex");
    return owner_mutex == NULL ? 1 : 0;
}

ERL_NIF_INIT(mob_ble_nif, nif_funcs, nif_load, NULL, NULL, NULL)
