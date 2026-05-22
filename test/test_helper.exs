ExUnit.start()

# The umbrella test runner starts applications before loading individual test
# files. Application lifecycle tests call MobBle.Application.start/2 directly,
# so leave :mob_ble stopped unless a test starts it explicitly.
Application.stop(:mob_ble)
