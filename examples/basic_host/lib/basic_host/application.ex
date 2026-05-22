defmodule BasicHost.Application do
  @moduledoc false

  use Application

  @impl true
  def start(_type, _args) do
    children = [
      {BasicHost.Transport, local_name: "mob-ble-example", native?: false}
    ]

    Supervisor.start_link(children, strategy: :one_for_one, name: BasicHost.Supervisor)
  end
end
