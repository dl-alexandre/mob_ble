defmodule Mob.Ble.Internal.CarrierDecision do
  @moduledoc false
  # Internal carrier policy ledger for Mob.Ble.
  #
  # Records the carrier evidence boundary as data only — it does not touch
  # native code, scan, advertise, fetch, route, persist, ACK, retry, encrypt,
  # authenticate, fragment, or run background work.
  #
  # The canonical iOS↔Android full-envelope route is MB legacy beacon cue
  # plus GATT fetch (see `Mob.Ble.carrier/0`). Other carriers are recorded
  # here with their status, inline evidence summaries, blocked claims, and a
  # short "why." Published packages must not require local artifact paths to
  # make this decision understandable.
  #
  # The four upstream handoff topics that anchor this decision are:
  #
  #   01 — iOS foreground custom-data limitation (mob_dev)
  #   02 — Extended advertising / AUX iOS limitation (mob_dev)
  #   03 — MB+GATT as canonical full-envelope path (mob)
  #   04 — Hybrid test scaffolding retention (mob_dev)
  #
  # Public read-only surface goes through `Mob.Ble.Diagnostics` — do not
  # call this module from outside Mob.Ble.

  defmodule Carrier do
    @moduledoc false

    @derive {JSON.Encoder, only: [:id, :direction, :status, :evidence, :blocked_claims, :notes]}
    @enforce_keys [:id, :direction, :status, :evidence, :blocked_claims, :notes]
    defstruct @enforce_keys

    @type id ::
            :manufacturer_data_legacy_beacon_observe
            | :full_mx_extended_advert_observe
            | :manufacturer_data_legacy_beacon_emit
            | :service_uuid_identity_advert
            | :service_data_beacon_ref
            | :local_name_encoded_beacon_ref
            | :mb_gatt

    @type direction :: :observe | :emit | :bidirectional

    @type status ::
            :implemented_unvalidated
            | :hardware_validated
            | :phy_blocked
            | :not_selected
            | :insufficient_for_beacon_ref
            | :candidate_unvalidated
            | :rejected

    @type t :: %__MODULE__{
            id: id(),
            direction: direction(),
            status: status(),
            evidence: [binary()],
            blocked_claims: [atom()],
            notes: [binary()]
          }
  end

  @upstream_issue_01 "upstream handoff 01: iOS foreground custom-data limitation"
  @upstream_issue_02 "upstream handoff 02: extended advertising / AUX iOS limitation"
  @upstream_issue_03 "upstream handoff 03: MB+GATT as canonical full-envelope path"
  @upstream_issue_04 "upstream handoff 04: hybrid test scaffolding retention"

  @carriers [
    %{
      id: :mb_gatt,
      direction: :bidirectional,
      status: :hardware_validated,
      evidence: [
        @upstream_issue_03,
        "2026-05-18 hardware summary: MB legacy beacon cue plus GATT fetch validated on SM-T390 and SM-T577U Android devices with iPhone/iPad iOS responders.",
        "Production BleSelfTest path observed full-envelope fetch, parse, and terminal success across independent device-pair runs."
      ],
      blocked_claims: [],
      notes: [
        "MB legacy 22-byte beacon cue + GATT fetch — the only cross-platform route that reliably delivers full MX envelopes (>31 bytes) between Android and iOS.",
        "Validated across four independent device-pair runs (iPhone 13 ↔ SM-T577U, SM-T390, R52, plus reverse).",
        "Positive MB+GATT round-trips confirmed on SM-T390 (API 28, awake) and SM-T577U (API 33) through the production BleSelfTest path."
      ]
    },
    %{
      id: :manufacturer_data_legacy_beacon_observe,
      direction: :observe,
      status: :hardware_validated,
      evidence: [
        "historical iOS foreground scanner sources (pre-extraction)",
        "2026-05-15 hardware summary: Android SM-T577U to iPhone 13 foreground scan observed MB legacy beacon manufacturer data."
      ],
      blocked_claims: [
        :ios_legacy_beacon_gossip,
        :ios_parity_claim
      ],
      notes: [
        "Foreground scanner code decodes the 22-byte legacy beacon manufacturer data (FFFF 4D 42).",
        "Hardware capture on 2026-05-15 proves Android SM-T577U → iPhone 13 legacy-beacon observation.",
        "Observe is hardware-validated; iOS-origin emit + cross-radio receipt remain unproven, so gossip/parity claims stay blocked."
      ]
    },
    %{
      id: :full_mx_extended_advert_observe,
      direction: :observe,
      status: :phy_blocked,
      evidence: [
        @upstream_issue_02,
        "docs/BLE_BRIDGE.md#extended-advertising-aux-delivery-limitation",
        "2026-05-17 hardware summary: Android emitted 80-byte full-MX extended adverts; iPad12,1 received MB legacy beacons but no direct full-MX AUX payload callbacks."
      ],
      blocked_claims: [
        :ios_full_mx_direct_advert_receive,
        :ios_full_envelope_advert_direct,
        :ios_parity_claim
      ],
      notes: [
        "Android emitted extended advertising sets (AUX_ADV_IND, setLegacyMode(false)) carrying 80-byte full MX envelopes in scan-response and advertising data; iOS CBCentralManager didDiscover never received the payload.",
        "Bluetooth logs showed MB legacy beacons (276 sightings in the iPad12,1 run) and zero FFFF 4D 58 / custom service-data lines from the extended set.",
        "Use MB legacy beacon + GATT fetch for full-envelope delivery to iOS (see :mb_gatt)."
      ]
    },
    %{
      id: :manufacturer_data_legacy_beacon_emit,
      direction: :emit,
      status: :implemented_unvalidated,
      evidence: [
        "historical iOS foreground scanner sources (pre-extraction)",
        "2026-05-15 and 2026-05-17 hardware summaries: iOS foreground bridge can initiate MB beacon dispatch, but iOS-origin cross-radio receive proof remains incomplete."
      ],
      blocked_claims: [
        :ios_legacy_beacon_gossip,
        :ios_one_hop_gossip_hardware_proof,
        :ios_parity_claim
      ],
      notes: [
        "The foreground iOS bridge can advertise the 22-byte MB beacon reference through CBAdvertisementDataManufacturerDataKey.",
        "Used as the no-GATT fallback cue for the GATT fetch responder; the harness drives the same path with the auto-beacon option.",
        "iPad evidence records beacon dispatch but zero matched Android receive lines, so iOS-origin gossip/parity claims stay blocked."
      ]
    },
    %{
      id: :service_uuid_identity_advert,
      direction: :emit,
      status: :insufficient_for_beacon_ref,
      evidence: ["historical iOS foreground scanner sources (pre-extraction)"],
      blocked_claims: [
        :ios_legacy_beacon_gossip,
        :message_reference_delivery,
        :ios_parity_claim
      ],
      notes: [
        "The iOS peripheral advertises the service UUID for peer discovery.",
        "A service UUID alone does not carry message_id_hash, sender_peer_hash, payload_kind, or envelope_version — it is not a beacon-ref carrier."
      ]
    },
    %{
      id: :service_data_beacon_ref,
      direction: :emit,
      status: :rejected,
      evidence: [
        @upstream_issue_01,
        "2026-05-18 hardware summary: iPhone 13 plus SM-T577U direct-MX service-data experiments showed iOS foreground custom data restrictions on emit and receive."
      ],
      blocked_claims: [
        :ios_legacy_beacon_gossip,
        :ios_hardware_participation,
        :ios_parity_claim
      ],
      notes: [
        "Rejected after 2026-05-18 bidirectional hardware validation on iPhone 13 + SM-T577U.",
        "iOS emit: CoreBluetooth foreground restrictions drop third-party manufacturer data and custom 128-bit service data; iOS_HYBRID_STARTED logs succeed while the radio transmits nothing matching (recapture-3, messageId f1aa757a…).",
        "iOS receive: scanForPeripherals(withServices: nil) excludes extended adverts on custom 128-bit UUIDs; Android-emitted MB cues arrive (52 sightings) but zero direct-MX service data on …1001 (recapture-4, messageId db9ae255…).",
        "Both blockers are iOS platform restrictions, not code defects. MB legacy beacon + GATT fetch is the canonical iOS↔Android full-envelope route."
      ]
    },
    %{
      id: :local_name_encoded_beacon_ref,
      direction: :emit,
      status: :rejected,
      evidence: ["Mob.Ble.Internal.CarrierDecision"],
      blocked_claims: [
        :ios_legacy_beacon_gossip,
        :ios_parity_claim
      ],
      notes: [
        "Encoding beacon refs into the advertised local name is rejected for this project boundary.",
        "It would be fragile, user-visible, and inconsistent with the canonical manufacturer-data ingress.",
        "Do not use local name text as a message reference transport."
      ]
    }
  ]

  @hybrid_test_scaffolding_note %{
    issue: @upstream_issue_04,
    note:
      "Hybrid direct-MX + MB scaffolding (IOSAuxFullMxAdvertSmokeTest, " <>
        "IOSHybridDirectMxReceiveTest, auto-direct-mx-hybrid-advertise) " <>
        "is intentionally retained: same code re-validates the direct carrier cheaply " <>
        "if a future iOS release relaxes foreground manufacturer-data / custom " <>
        "service-data restrictions."
  }

  @spec carriers() :: [Carrier.t()]
  def carriers, do: Enum.map(@carriers, &struct!(Carrier, &1))

  @spec hybrid_test_scaffolding_note() :: %{issue: binary(), note: binary()}
  def hybrid_test_scaffolding_note, do: @hybrid_test_scaffolding_note

  @doc """
  The active carrier id. Single source of truth — `Mob.Ble.carrier/0`
  delegates here. Anything not matching this is rejected.
  """
  @spec active() :: :mb_gatt
  def active, do: :mb_gatt

  @doc """
  Returns `:ok` for the active carrier, otherwise a reason map suitable for
  `Mob.Ble.CarrierRejectedError`.
  """
  @spec check(atom()) :: :ok | {:rejected, %{reason: binary(), diagnostics: map() | nil}}
  def check(carrier_id) when is_atom(carrier_id) do
    if carrier_id == active() do
      :ok
    else
      case Enum.find(carriers(), &(&1.id == carrier_id)) do
        %Carrier{status: :rejected} = c ->
          {:rejected,
           %{
             reason: List.first(c.notes) || "rejected by carrier policy",
             diagnostics: %{id: c.id, status: c.status, blocked_claims: c.blocked_claims}
           }}

        %Carrier{} = c ->
          {:rejected,
           %{
             reason:
               "carrier #{inspect(carrier_id)} has status #{inspect(c.status)} — only #{inspect(active())} is validated",
             diagnostics: %{id: c.id, status: c.status, blocked_claims: c.blocked_claims}
           }}

        nil ->
          {:rejected,
           %{
             reason: "unknown carrier #{inspect(carrier_id)} — only #{inspect(active())} is validated",
             diagnostics: nil
           }}
      end
    end
  end

  @spec snapshot() :: map()
  def snapshot do
    carriers = carriers()

    %{
      decision_version: 2,
      boundary: :ios_advert_only_carrier_decision,
      active_carrier: active(),
      current_ios_observe_carrier: :manufacturer_data_legacy_beacon_observe,
      current_ios_emit_carrier: :manufacturer_data_legacy_beacon_emit,
      ios_legacy_beacon_observe_implemented?: true,
      ios_legacy_beacon_observe_hardware_validated?: true,
      ios_legacy_beacon_emit_implemented?: true,
      ios_legacy_beacon_emit_cross_radio_validated?: false,
      ios_full_mx_direct_advert_receive_allowed?: false,
      ios_legacy_beacon_gossip_implemented?: false,
      ios_legacy_beacon_gossip_claim_allowed?: false,
      ios_parity_claim_allowed?: false,
      carriers: carriers,
      hybrid_test_scaffolding: @hybrid_test_scaffolding_note,
      upstream_issues: [
        @upstream_issue_01,
        @upstream_issue_02,
        @upstream_issue_03,
        @upstream_issue_04
      ],
      recommended_next_step: :hardware_validate_observe_before_selecting_emit_carrier,
      blocked_claims: blocked_claims(carriers),
      notes: [
        "iOS observe and iOS emit are separate claims.",
        "The validated cross-platform full-message mode is MB legacy beacon cue + GATT fetch (carrier :mb_gatt).",
        "Foreground iOS MB beacon emission exists, but iOS-origin cross-radio receipt remains unproven.",
        "Direct full-MX extended advertising is disabled for iOS because hardware scans did not deliver AUX manufacturer data through CoreBluetooth.",
        "iOS gossip and parity claims stay blocked until iOS-origin emission is observer-captured, replay-normalized, and bounded by negative fixtures."
      ]
    }
  end

  defp blocked_claims(carriers) do
    carriers
    |> Enum.flat_map(& &1.blocked_claims)
    |> Enum.uniq()
    |> Enum.sort()
  end
end
