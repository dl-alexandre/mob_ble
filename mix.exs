defmodule Mob.Ble.MixProject do
  use Mix.Project

  @github_url "https://github.com/dl-alexandre/mob_ble"
  @version "0.1.0"
  @description "Production BLE transport plugin for the mob framework (MB legacy beacon + GATT fetch carrier)"

  def project do
    [
      app: :mob_ble,
      version: @version,
      elixir: "~> 1.18",
      start_permanent: Mix.env() == :prod,
      test_coverage: [summary: [threshold: 90]],
      compilers: [:elixir_make] ++ Mix.compilers(),
      make_clean: ["clean"],
      deps: deps(),
      dialyzer: dialyzer(),
      description: @description,
      package: package(),
      source_url: @github_url,
      homepage_url: @github_url,
      docs: [
        main: "readme",
        extras: [
          "README.md",
          "CHANGELOG.md",
          "docs/ROADMAP.md",
          "docs/PERFORMANCE.md",
          "examples/basic_host/README.md",
          "LICENSE"
        ]
      ]
    ]
  end

  defp package do
    [
      maintainers: ["dl-alexandre"],
      licenses: ["Apache-2.0"],
      links: %{
        "GitHub" => @github_url,
        "Changelog" => "#{@github_url}/blob/main/CHANGELOG.md",
        "mob" => "https://github.com/GenericJam/mob",
        "mob_dev" => "https://github.com/GenericJam/mob_dev"
      },
      files: ~w(
        lib
        src
        priv/mob_plugin.exs
        priv/native/android/.gitignore
        priv/native/android/EXTRACTION_INVENTORY.md
        priv/native/android/assets
        priv/native/android/build.gradle
        priv/native/android/consumer-rules.pro
        priv/native/android/gradle.properties
        priv/native/android/jni/.gitkeep
        priv/native/android/jni/CMakeLists.txt
        priv/native/android/jni/build.zig
        priv/native/android/jni/driver_tab_decls.cinc
        priv/native/android/jni/driver_tab_entries.cinc
        priv/native/android/jni/mob_ble_jni_hooks.c
        priv/native/android/jni/mob_ble_nif.c
        priv/native/android/jni/static_nifs.list
        priv/native/android/res
        priv/native/android/settings.gradle
        priv/native/android/src/main
        priv/native/mob_ble_nif_stub.c
        priv/native/ios
        mix.exs
        Makefile
        README.md
        CHANGELOG.md
        docs
        examples
        scripts
        LICENSE
      )
    ]
  end

  def application do
    [
      mod: {MobBle.Application, []},
      extra_applications: [:logger]
    ]
  end

  defp deps do
    [
      # No runtime dependency on meshx_transport_ble (or any meshx_* package).
      # The canonical Bridge behaviour is defined locally as Mob.Ble.Bridge.
      # This package is fully self-contained for the `mob` plugin ecosystem.
      # (See docs/mob_ble_bridge_migration.md — Phase 1 complete.)
      {:elixir_make, "~> 0.9.0", runtime: false},
      {:dialyxir, "~> 1.4", only: [:dev, :test], runtime: false},
      {:ex_doc, "~> 0.40.2", only: :dev, runtime: false}
    ]
  end

  defp dialyzer do
    [
      plt_local_path: "_build/plts",
      plt_core_path: "_build/plts",
      plt_add_apps: [:mix, :ex_unit],
      flags: [:error_handling, :unknown, :unmatched_returns]
    ]
  end
end
