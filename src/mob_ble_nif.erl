%% mob_ble_nif.erl — static-loadable BLE NIF stub for the mob_ble plugin.
%% Native implementations are provided by priv/native/android/jni/mob_ble_nif.c
%% on Android and by the corresponding static NIF on iOS.
-module(mob_ble_nif).

-export([start_scan/1, start_advertising/2, stop/1, send_ping/3]).
-nifs([start_scan/1, start_advertising/2, stop/1, send_ping/3]).
-on_load(init/0).

init() ->
    case code:priv_dir(mob_ble) of
        {error, _} ->
            erlang:load_nif("mob_ble_nif", 0);
        PrivDir ->
            erlang:load_nif(filename:join(PrivDir, "mob_ble_nif"), 0)
    end.

start_scan(_Owner) -> erlang:nif_error(not_loaded).
start_advertising(_Owner, _LocalName) -> erlang:nif_error(not_loaded).
stop(_Owner) -> erlang:nif_error(not_loaded).
send_ping(_Owner, _PeerId, _Payload) -> erlang:nif_error(not_loaded).
