#include "erl_nif.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

extern void mob_ble_start_scan(void);
extern void mob_ble_start_advertising(const char *local_name);
extern void mob_ble_stop(void);
extern void mob_ble_send_ping(const char *peer_id, const uint8_t *payload, int32_t payload_len);

static ErlNifMutex *owner_mutex = NULL;
static ErlNifPid owner_pid;
static int owner_set = 0;

static ERL_NIF_TERM atom(ErlNifEnv *env, const char *name) {
    return enif_make_atom(env, name);
}

static ERL_NIF_TERM ok(ErlNifEnv *env) {
    return atom(env, "ok");
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

    char *copy = (char *)malloc(bin.size + 1);
    if (copy == NULL) {
        return NULL;
    }

    memcpy(copy, bin.data, bin.size);
    copy[bin.size] = '\0';
    return copy;
}

static ERL_NIF_TERM binary_string(ErlNifEnv *env, const char *value) {
    size_t len = strlen(value);
    ERL_NIF_TERM out;
    unsigned char *data = enif_make_new_binary(env, len, &out);
    memcpy(data, value, len);
    return out;
}

static ERL_NIF_TERM binary_bytes(ErlNifEnv *env, const uint8_t *value, uint32_t len) {
    ERL_NIF_TERM out;
    unsigned char *data = enif_make_new_binary(env, len, &out);
    if (len > 0 && value != NULL) {
        memcpy(data, value, len);
    } else if (len > 0) {
        memset(data, 0, len);
    }
    return out;
}

// Minimal base64 encode for binary fields (matches Android toBase64 expectation in JSON)
static const char b64_table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static char *b64_encode(const uint8_t *data, size_t input_len, size_t *output_len) {
    if (data == NULL || input_len == 0) {
        char *empty = (char *)malloc(1);
        if (empty) empty[0] = '\0';
        if (output_len) *output_len = 0;
        return empty;
    }
    size_t out_len = 4 * ((input_len + 2) / 3);
    char *out = (char *)malloc(out_len + 1);
    if (!out) return NULL;
    size_t i = 0, j = 0;
    while (i + 2 < input_len) {
        uint32_t triple = (data[i] << 16) | (data[i+1] << 8) | data[i+2];
        out[j++] = b64_table[(triple >> 18) & 0x3F];
        out[j++] = b64_table[(triple >> 12) & 0x3F];
        out[j++] = b64_table[(triple >> 6) & 0x3F];
        out[j++] = b64_table[triple & 0x3F];
        i += 3;
    }
    if (i < input_len) {
        uint32_t triple = data[i] << 16;
        if (i + 1 < input_len) triple |= data[i+1] << 8;
        out[j++] = b64_table[(triple >> 18) & 0x3F];
        out[j++] = b64_table[(triple >> 12) & 0x3F];
        out[j++] = (i + 1 < input_len) ? b64_table[(triple >> 6) & 0x3F] : '=';
        out[j++] = '=';
    }
    out[j] = '\0';
    if (output_len) *output_len = j;
    return out;
}

static void free_b64(char *p) { if (p) free(p); }

// Very basic JSON string escaper for " \ and common controls (sufficient for error/status/ids)
static void json_escape(const char *in, char *out, size_t out_sz) {
    size_t o = 0;
    for (size_t i = 0; in[i] && o + 2 < out_sz; i++) {
        char c = in[i];
        if (c == '"') { out[o++] = '\\'; out[o++] = '"'; }
        else if (c == '\\') { out[o++] = '\\'; out[o++] = '\\'; }
        else if (c == '\n') { out[o++] = '\\'; out[o++] = 'n'; }
        else if (c == '\r') { out[o++] = '\\'; out[o++] = 'r'; }
        else if (c == '\t') { out[o++] = '\\'; out[o++] = 't'; }
        else out[o++] = c;
    }
    out[o] = '\0';
}

static ERL_NIF_TERM make_json_binary(ErlNifEnv *env, const char *json) {
    size_t len = strlen(json);
    ERL_NIF_TERM out;
    unsigned char *data = enif_make_new_binary(env, len, &out);
    memcpy(data, json, len);
    return out;
}

// Deliver a pre-built JSON string as the bridge_event payload.
// Matches the v1 contract used by Android BleEvent and decoded by
// Mob.Ble.Internal.BridgeProtocol + MobileBridge.
static void send_json_event(ErlNifEnv *env, const char *json) {
    enif_mutex_lock(owner_mutex);
    if (!owner_set) {
        enif_mutex_unlock(owner_mutex);
        return;
    }

    ErlNifPid pid = owner_pid;
    enif_mutex_unlock(owner_mutex);

    ERL_NIF_TERM json_bin = make_json_binary(env, json);

    ERL_NIF_TERM msg = enif_make_tuple3(
        env,
        atom(env, "Elixir.Mob.Ble.MobileBridge"),
        atom(env, "bridge_event"),
        json_bin
    );

    enif_send(NULL, &pid, env, msg);
}

// Helper to build small JSON (no external lib; sufficient for event shapes)
static void emit_json_event(const char *json) {
    ErlNifEnv *env = enif_alloc_env();
    send_json_event(env, json);
    enif_free_env(env);
}

// --- Emitters producing v1 JSON matching Android BleEvent shapes ---
// These are called from Swift via the silgen declarations.

void mob_ble_emit_status(const char *status) {
    char escaped[512];
    json_escape(status ? status : "", escaped, sizeof(escaped));
    char json[1024];
    snprintf(json, sizeof(json),
        "{\"v\":1,\"event\":\"status\",\"detail\":\"%s\"}",
        escaped);
    emit_json_event(json);
}

void mob_ble_emit_error(const char *message) {
    char escaped[1024];
    json_escape(message ? message : "", escaped, sizeof(escaped));
    char json[2048];
    snprintf(json, sizeof(json),
        "{\"v\":1,\"event\":\"error\",\"kind\":\"bridge\",\"detail\":\"%s\"}",
        escaped);
    emit_json_event(json);
}

void mob_ble_emit_connected(const char *peer_id) {
    // Map connected to a peer_up style for transport visibility
    char json[512];
    snprintf(json, sizeof(json),
        "{\"v\":1,\"event\":\"peer_up\",\"peer_id\":\"%s\",\"metadata\":{\"via\":\"gatt\"}}",
        peer_id);
    emit_json_event(json);
}

void mob_ble_emit_disconnected(const char *peer_id) {
    char json[256];
    snprintf(json, sizeof(json),
        "{\"v\":1,\"event\":\"peer_down\",\"peer_id\":\"%s\"}",
        peer_id);
    emit_json_event(json);
}

// Legacy simple received (kept for compatibility during transition)
void mob_ble_emit_received(const char *peer_id, int32_t type, uint32_t msg_id, uint32_t byte_count) {
    (void)type; (void)msg_id; (void)byte_count;
    char json[512];
    snprintf(json, sizeof(json),
        "{\"v\":1,\"event\":\"frame\",\"peer_id\":\"%s\",\"frame\":\"\"}",
        peer_id);
    emit_json_event(json);
}

void mob_ble_emit_received_message_beacon(
    const char *device_id,
    int32_t rssi,
    int32_t beacon_version,
    int32_t envelope_version,
    const char *payload_kind,
    const uint8_t *message_id_hash,
    const uint8_t *sender_peer_id_hash,
    const uint8_t *advertisement,
    uint32_t advertisement_len,
    const uint8_t *beacon_payload,
    uint32_t beacon_payload_len,
    const uint8_t *manufacturer_data,
    uint32_t manufacturer_data_len,
    uint32_t company_identifier
) {
    size_t b64_mid_len=0, b64_snd_len=0, b64_adv_len=0, b64_bpl_len=0, b64_mfg_len=0;
    char *b64_mid = b64_encode(message_id_hash, 8, &b64_mid_len); // typical 8-byte hashes
    char *b64_snd = b64_encode(sender_peer_id_hash, 8, &b64_snd_len);
    char *b64_adv = b64_encode(advertisement, advertisement_len, &b64_adv_len);
    char *b64_bpl = b64_encode(beacon_payload, beacon_payload_len, &b64_bpl_len);
    char *b64_mfg = b64_encode(manufacturer_data, manufacturer_data_len, &b64_mfg_len);

    char dev_esc[128], pk_esc[64];
    json_escape(device_id ? device_id : "", dev_esc, sizeof(dev_esc));
    json_escape(payload_kind ? payload_kind : "", pk_esc, sizeof(pk_esc));

    char json[4096];
    snprintf(json, sizeof(json),
        "{\"v\":1,\"event\":\"received_message_beacon\",\"beacon_version\":%d,\"envelope_version\":%d,\"payload_kind\":\"%s\",\"message_id_hash\":\"%s\",\"sender_peer_id_hash\":\"%s\",\"received_device_id\":\"%s\",\"received_at\":0,\"rssi\":%d,\"raw_transport_metadata\":{\"transport\":\"ble_ios_advertisement\",\"source_event\":\"advertisement_received\",\"received_device_id\":\"%s\",\"advertisement\":\"%s\",\"beacon_payload\":\"%s\",\"manufacturer_data\":\"%s\",\"company_identifier\":%u}}",
        beacon_version, envelope_version, pk_esc,
        b64_mid ? b64_mid : "", b64_snd ? b64_snd : "",
        dev_esc, rssi, dev_esc,
        b64_adv ? b64_adv : "", b64_bpl ? b64_bpl : "", b64_mfg ? b64_mfg : "",
        company_identifier);
    emit_json_event(json);

    free_b64(b64_mid); free_b64(b64_snd); free_b64(b64_adv); free_b64(b64_bpl); free_b64(b64_mfg);
}

void mob_ble_emit_received_message(
    const char *device_id,
    int32_t rssi,
    int64_t received_at_ms,
    const uint8_t *message_id,
    uint32_t message_id_len,
    const char *sender_peer_id,
    const char *recipient_peer_id,
    const uint8_t *envelope,
    uint32_t envelope_len,
    const uint8_t *advertisement,
    uint32_t advertisement_len,
    const uint8_t *message_payload,
    uint32_t message_payload_len,
    const uint8_t *manufacturer_data,
    uint32_t manufacturer_data_len,
    uint32_t company_identifier
) {
    size_t b64_env_len = 0, b64_msg_len = 0, b64_mfg_len = 0, b64_adv_len = 0, b64_mid_len = 0;
    char *b64_env = b64_encode(envelope, envelope_len, &b64_env_len);
    char *b64_mid = b64_encode(message_id, message_id_len, &b64_mid_len);
    char *b64_mfg = b64_encode(manufacturer_data, manufacturer_data_len, &b64_mfg_len);
    char *b64_adv = b64_encode(advertisement, advertisement_len, &b64_adv_len);
    // Prefer envelope for full MX; fall back to message_payload for compatibility
    char *b64_payload = b64_encode(envelope_len > 0 ? envelope : message_payload,
                                   envelope_len > 0 ? envelope_len : message_payload_len,
                                   &b64_msg_len);

    char dev_esc[128], snd_esc[128], recip_esc[128];
    json_escape(device_id ? device_id : "", dev_esc, sizeof(dev_esc));
    json_escape(sender_peer_id ? sender_peer_id : "", snd_esc, sizeof(snd_esc));
    const char *recip_val = recipient_peer_id ? recipient_peer_id : NULL;
    json_escape(recip_val ? recip_val : "", recip_esc, sizeof(recip_esc));

    char json[8192];
    char recip_part[256];
    if (recip_val) {
        snprintf(recip_part, sizeof(recip_part), "\"%s\"", recip_esc);
    } else {
        snprintf(recip_part, sizeof(recip_part), "null");
    }
    // Valid JSON: recipient as string or null; all binaries base64; full data used
    snprintf(json, sizeof(json),
        "{\"v\":1,\"event\":\"received_message\",\"message_id\":\"%s\",\"sender_peer_id\":\"%s\",\"recipient_peer_id\":%s,\"received_device_id\":\"%s\",\"received_at\":%lld,\"rssi\":%d,\"envelope\":\"%s\",\"raw_transport_metadata\":{\"transport\":\"ble_ios_gatt_fetch\",\"source_event\":\"gatt_fetch_response\",\"received_device_id\":\"%s\",\"company_identifier\":%u,\"advertisement\":\"%s\",\"manufacturer_data\":\"%s\"}}",
        b64_mid ? b64_mid : "",
        snd_esc,
        recip_part,
        dev_esc,
        (long long)received_at_ms,
        rssi,
        b64_env ? b64_env : (b64_payload ? b64_payload : ""),
        dev_esc,
        company_identifier,
        b64_adv ? b64_adv : "",
        b64_mfg ? b64_mfg : "");

    emit_json_event(json);

    free_b64(b64_env);
    free_b64(b64_mid);
    free_b64(b64_mfg);
    free_b64(b64_adv);
    free_b64(b64_payload);
}

// --- NIF bindings (identical surface to Android) ---

static ERL_NIF_TERM nif_start_scan(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
    if (argc != 1 || !set_owner(env, argv[0])) {
        return enif_make_badarg(env);
    }

    mob_ble_start_scan();
    return ok(env);
}

static ERL_NIF_TERM nif_start_advertising(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
    if (argc != 2 || !set_owner(env, argv[0])) {
        return enif_make_badarg(env);
    }

    char *local_name = copy_string_arg(env, argv[1]);
    if (local_name == NULL) {
        return enif_make_badarg(env);
    }

    mob_ble_start_advertising(local_name);
    free(local_name);
    return ok(env);
}

static ERL_NIF_TERM nif_stop(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
    if (argc != 1 || !set_owner(env, argv[0])) {
        return enif_make_badarg(env);
    }

    mob_ble_stop();
    return ok(env);
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
        free(peer_id);
        return enif_make_badarg(env);
    }

    mob_ble_send_ping(peer_id, payload.data, (int32_t)payload.size);
    free(peer_id);
    return ok(env);
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
