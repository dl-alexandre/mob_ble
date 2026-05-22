#include "erl_nif.h"

static ERL_NIF_TERM atom(ErlNifEnv *env, const char *name) {
    return enif_make_atom(env, name);
}

static ERL_NIF_TERM error_tuple(ErlNifEnv *env, const char *reason) {
    return enif_make_tuple2(env, atom(env, "error"), atom(env, reason));
}

static ERL_NIF_TERM nif_start_scan(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
    if (argc != 1) {
        return enif_make_badarg(env);
    }
    return error_tuple(env, "native_not_available");
}

static ERL_NIF_TERM nif_start_advertising(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
    if (argc != 2) {
        return enif_make_badarg(env);
    }
    return error_tuple(env, "native_not_available");
}

static ERL_NIF_TERM nif_stop(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
    if (argc != 1) {
        return enif_make_badarg(env);
    }
    return error_tuple(env, "native_not_available");
}

static ERL_NIF_TERM nif_send_ping(ErlNifEnv *env, int argc, const ERL_NIF_TERM argv[]) {
    if (argc != 3) {
        return enif_make_badarg(env);
    }
    return error_tuple(env, "native_not_available");
}

static ErlNifFunc nif_funcs[] = {
    {"start_scan", 1, nif_start_scan, 0},
    {"start_advertising", 2, nif_start_advertising, 0},
    {"stop", 1, nif_stop, 0},
    {"send_ping", 3, nif_send_ping, 0}
};

ERL_NIF_INIT(mob_ble_nif, nif_funcs, NULL, NULL, NULL, NULL)
