# Basic Host Example

This is a minimal host application that owns a `mob_ble` bridge process.

It starts `BasicHost.Transport`, which:

- calls `MobBle.bridge_module/0`;
- starts the bridge with itself as `:event_target`;
- handles `{:ble_peer_up, ...}`, `{:ble_peer_down, ...}`, and
  `{:ble_frame, ...}` messages.

Run it from this directory:

```sh
mix deps.get
mix run --no-halt
```

The example defaults to `native?: false`, so it is safe to run on a development
machine without Android/iOS native linkage. Set `native?: true` only in a real
mobile host where the `mob_ble` native sources and NIF are linked by the app.

For quick inspection in `iex`:

```sh
iex -S mix
```

```elixir
BasicHost.Transport.peers()
```
