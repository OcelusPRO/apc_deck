import { useEffect, useRef, useState, type ReactNode } from "react";
import { Banner, Button, Piano, Section, Segmented, Slider, cx, noteName, padHex, textOn, usePluginEvent, usePluginState } from "@apcdeck/web";
import type { Knob, SynthState } from "./types";

/** Couleurs des pads, mêmes index de palette que le plugin (SynthPlugin). */
const WAVE = { SINE: 33, TRIANGLE: 21, SAW: 9, SQUARE: 53, PULSE: 45 } as Record<string, number>;
const ARP_MODE = { UP: 33, DOWN: 37, UP_DOWN: 29, RANDOM: 57 } as Record<string, number>;
const ARP_RATE = { QUARTER: 3, EIGHTH: 17, SIXTEENTH: 21, EIGHTH_TRIPLET: 49 } as Record<string, number>;
const CHORD = { OFF: 2, MAJOR: 57, MINOR: 49, SEVENTH: 53, SUS4: 37 } as Record<string, number>;
const NOTE_LABELS = ["Do", "Do#", "Ré", "Ré#", "Mi", "Fa", "Fa#", "Sol", "Sol#", "La", "La#", "Si"];
const TOGGLES = [
  ["arp", "Arpège"],
  ["latch", "Latch"],
  ["mono", "Mono + glissando"],
  ["echo", "Écho"],
  ["vibrato", "Vibrato"],
] as const;

type Call = (action: string, data?: unknown) => Promise<unknown>;

export function App() {
  const { state, call, error } = usePluginState<SynthState>();
  const [notes, setNotes] = useState<number[]>([]);
  useEffect(() => setNotes(state?.notes ?? []), [state?.notes]);
  usePluginEvent<number[]>("notes", setNotes);

  if (!state) return <div className="p-6 text-muted">{error ?? "Chargement…"}</div>;

  return (
    <main className="mx-auto flex max-w-[1100px] flex-col gap-6 px-6 py-5">
      <header className="flex items-center gap-3">
        <h1 className="m-0 text-xl font-semibold">Synthé</h1>
        <span className="flex-1 text-muted">{notes.length > 0 && `${notes.length} note${notes.length > 1 ? "s" : ""}`}</span>
        <Button onClick={() => call("release")} title="Les notes se terminent avec leur relâchement">Coupure douce</Button>
        <Button variant="danger" onClick={() => call("panic")} title="Silence immédiat">Coupure nette</Button>
      </header>

      {error && <Banner tone="error">{error}</Banner>}
      {!state.audio && <Banner tone="error">Aucune sortie audio disponible : {state.audioError ?? "?"}</Banner>}
      {!state.foreground && (
        <Banner>
          <span className="flex-1">Le synthé ne joue que lorsqu'il est au premier plan sur l'APC.</span>
          <Button variant="primary" onClick={() => call("activate")}>L'ouvrir sur l'APC</Button>
        </Banner>
      )}

      <Section title="Forme d'onde" hint="rangée 1 des pads">
        <Segmented options={state.waveforms} value={state.waveform} onChange={(id) => call("waveform", { waveform: id })} />
      </Section>

      <Section title="Modes de jeu" hint="rangée 4 des pads">
        <div className="flex flex-wrap gap-1.5">
          {TOGGLES.map(([id, label]) => (
            <Button key={id} variant={state.toggles[id] ? "primary" : "outline"} onClick={() => call("toggle", { name: id })}>
              {label}
            </Button>
          ))}
        </div>
        <Row label="Arpège">
          <Segmented options={state.arpModes} value={state.arpMode} onChange={(id) => call("arpMode", { value: id })} />
          <Segmented options={state.arpRates} value={state.arpRate} onChange={(id) => call("arpRate", { value: id })} />
        </Row>
        <Row label="Accords">
          <Segmented options={state.chords} value={state.chord} onChange={(id) => call("chord", { value: id })} />
        </Row>
        <Row label="Tempo">
          <Tempo state={state} call={call} />
        </Row>
      </Section>

      <Section title="Presets" hint="rangée 5 des pads : appui = charger, Shift + appui = enregistrer">
        <Presets state={state} call={call} />
      </Section>

      <Section title="Fichiers MIDI" hint="ligne rouge (boutons 1 à 8) : appui = lecture / arrêt">
        <Songs state={state} call={call} />
      </Section>

      <Section title="Réglages" hint="potars 1 à 8">
        <div className="grid grid-cols-2 gap-x-6 gap-y-3.5 md:grid-cols-4">
          {state.knobs.map((k) => <KnobSlider key={k.name} knob={k} call={call} />)}
        </div>
      </Section>

      <Section title="Clavier" hint={notes.map(noteName).join(" ")}>
        <Piano
          low={48}
          high={72}
          height={120}
          pressed={new Set(notes)}
          onPress={(note, down) => call(down ? "noteOn" : "noteOff", { note })}
        />
      </Section>

      <Section title="Sur l'APC" hint="quand le synthé est au premier plan">
        <ApcMap state={state} notes={notes} />
      </Section>
    </main>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <span className="w-[70px] text-muted">{label}</span>
      {children}
    </div>
  );
}

/** Curseur qui garde sa valeur locale pendant le glissement et limite le débit d'envoi. */
function KnobSlider({ knob, call }: { knob: Knob; call: Call }) {
  const [local, setLocal] = useState<number | null>(null);
  const timer = useRef<number | undefined>(undefined);
  return (
    <Slider
      label={`${knob.knob}. ${knob.label}`}
      display={knob.display}
      value={local ?? knob.value}
      onChange={(v) => {
        setLocal(v);
        window.clearTimeout(timer.current);
        timer.current = window.setTimeout(() => {
          void call("set", { name: knob.name, value: v }).then(() => setLocal(null));
        }, 40);
      }}
    />
  );
}

function Tempo({ state, call }: { state: SynthState; call: Call }) {
  const [local, setLocal] = useState<number | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const bpm = local ?? state.bpm;
  return (
    <>
      <input
        type="range"
        min={state.bpmRange[0]}
        max={state.bpmRange[1]}
        step={1}
        value={bpm}
        className="w-64"
        onChange={(e) => {
          const v = Number(e.target.value);
          setLocal(v);
          window.clearTimeout(timer.current);
          timer.current = window.setTimeout(() => void call("bpm", { value: v }).then(() => setLocal(null)), 60);
        }}
      />
      <span className="text-accent tabular-nums">{bpm} BPM</span>
      <span className="text-xs text-muted">arpège et écho</span>
    </>
  );
}

function Presets({ state, call }: { state: SynthState; call: Call }) {
  return (
    <div className="grid grid-cols-4 gap-2.5 lg:grid-cols-8">
      {state.presets.map((p, slot) => (
        <div
          key={slot}
          className={cx(
            "flex flex-col gap-2 rounded-xl bg-surface-2 p-2.5",
            p.active && "outline-2 outline-accent",
          )}
        >
          <div className="flex items-center justify-between">
            <b className="text-[13px]">Preset {slot + 1}</b>
            {p.saved && (
              <button
                type="button"
                title="Vider cet emplacement"
                onClick={() => call("presetClear", { slot })}
                className="cursor-pointer text-xs text-muted hover:text-danger"
              >
                ✕
              </button>
            )}
          </div>
          <div className="h-2 rounded-full" style={{ background: p.saved && p.waveform ? padHex(WAVE[p.waveform] ?? 3) : "#ffffff14" }} />
          {p.saved ? (
            <div className="flex flex-col gap-1">
              <Button size="sm" variant={p.active ? "primary" : "outline"} onClick={() => call("presetLoad", { slot })}>
                {p.active ? "Son actuel" : "Charger"}
              </Button>
              <Button size="sm" variant="text" onClick={() => call("presetSave", { slot })}>Remplacer</Button>
            </div>
          ) : (
            <Button size="sm" onClick={() => call("presetSave", { slot })}>Enregistrer</Button>
          )}
        </div>
      ))}
    </div>
  );
}

const MAX_SONG_BYTES = 2 * 1024 * 1024;

function duration(seconds: number): string {
  const s = Math.round(seconds);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

/** Lit le fichier choisi et l'envoie au plugin en base64 (le corps des appels est du JSON). */
function upload(call: Call, slot: number, file: File) {
  if (file.size > MAX_SONG_BYTES) {
    window.alert(`« ${file.name} » est trop gros (maximum ${MAX_SONG_BYTES / 1024 / 1024} Mo).`);
    return;
  }
  const reader = new FileReader();
  reader.onload = () => {
    const url = String(reader.result);
    void call("songLoad", { slot, name: file.name, data: url.slice(url.indexOf(",") + 1) });
  };
  reader.readAsDataURL(file);
}

function Songs({ state, call }: { state: SynthState; call: Call }) {
  return (
    <>
      <div className="flex items-center gap-3">
        <Button variant={state.songLoop ? "primary" : "outline"} onClick={() => call("songLoop")}>Boucle</Button>
        <span className="text-xs text-muted">
          Fichiers .mid joués par le synthé avec le son actuel (la batterie, canal 10, est ignorée). Glisser un fichier sur un emplacement pour le charger.
        </span>
      </div>
      <div className="grid grid-cols-2 gap-2.5 md:grid-cols-4 lg:grid-cols-8">
        {state.songs.map((s, slot) => (
          <div
            key={slot}
            onDragOver={(e) => e.preventDefault()}
            onDrop={(e) => {
              e.preventDefault();
              const file = e.dataTransfer.files[0];
              if (file) upload(call, slot, file);
            }}
            className={cx("flex min-w-0 flex-col gap-2 rounded-xl bg-surface-2 p-2.5", s.playing && "outline-2 outline-accent")}
          >
            <div className="flex items-center justify-between">
              <b className="text-[13px]">Piste {slot + 1}</b>
              {s.name && (
                <button
                  type="button"
                  title="Vider cet emplacement"
                  onClick={() => call("songClear", { slot })}
                  className="cursor-pointer text-xs text-muted hover:text-danger"
                >
                  ✕
                </button>
              )}
            </div>
            <div className="min-h-8 text-xs leading-tight">
              {s.name ? (
                <>
                  <div className="truncate" title={s.name}>{s.name}</div>
                  <div className="text-muted">{duration(s.seconds ?? 0)} · {s.notes} notes</div>
                </>
              ) : (
                <span className="text-muted">Vide</span>
              )}
            </div>
            <div className="flex flex-col gap-1">
              {s.name && (
                <Button
                  size="sm"
                  variant={s.playing ? "primary" : "outline"}
                  disabled={!s.playing && (!state.foreground || !state.audio)}
                  onClick={() => call("songPlay", { slot })}
                >
                  {s.playing ? "■ Arrêter" : "▶ Jouer"}
                </Button>
              )}
              <label className="cursor-pointer rounded-lg px-2.5 py-1 text-center text-xs text-muted hover:text-text">
                {s.name ? "Remplacer" : "Charger un .mid"}
                <input
                  type="file"
                  accept=".mid,.midi,audio/midi"
                  className="hidden"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    e.target.value = "";
                    if (file) upload(call, slot, file);
                  }}
                />
              </label>
            </div>
          </div>
        ))}
      </div>
    </>
  );
}

// --- plan de l'APC --------------------------------------------------------------------------

interface PadInfo {
  text: string;
  color?: string;
  dim?: boolean;
  note?: { sharp: boolean; on: boolean };
}

function padInfo(state: SynthState, notes: number[], x: number, y: number): PadInfo {
  const label = (list: SynthState["arpModes"], id: string) => list.find((o) => o.id === id)?.label ?? id;
  if (y === 0) {
    const w = state.waveforms[x];
    if (w) return { text: w.label, color: padHex(WAVE[w.id] ?? 3), dim: w.id !== state.waveform };
    if (x === 6) return { text: "Coupure douce", color: padHex(9), dim: true };
    if (x === 7) return { text: "Coupure nette", color: padHex(5), dim: true };
  }
  const pc = y === 2 ? [0, 2, 4, 5, 7, 9, 11][x] : y === 1 ? [-1, 1, 3, -1, 6, 8, 10, -1][x] : undefined;
  if (pc !== undefined && pc >= 0) {
    return { text: NOTE_LABELS[pc] ?? "", note: { sharp: y === 1, on: notes.some((n) => n % 12 === pc) } };
  }
  if (y === 3) {
    const t = state.toggles;
    const pads: PadInfo[] = [
      { text: "Arpège", color: padHex(21), dim: !t.arp },
      { text: label(state.arpModes, state.arpMode), color: padHex(ARP_MODE[state.arpMode] ?? 3), dim: !t.arp },
      { text: label(state.arpRates, state.arpRate), color: padHex(ARP_RATE[state.arpRate] ?? 3), dim: !t.arp },
      { text: "Latch", color: padHex(9), dim: !t.latch },
      { text: `Accord ${label(state.chords, state.chord).toLowerCase()}`, color: padHex(CHORD[state.chord] ?? 2), dim: state.chord === "OFF" },
      { text: "Mono", color: padHex(33), dim: !t.mono },
      { text: "Écho", color: padHex(29), dim: !t.echo },
      { text: "Vibrato", color: padHex(17), dim: !t.vibrato },
    ];
    return pads[x] ?? { text: "" };
  }
  if (y === 4) {
    const p = state.presets[x];
    return p?.saved
      ? { text: `Preset ${x + 1}${p.active ? " ●" : ""}`, color: padHex(WAVE[p.waveform ?? ""] ?? 3) }
      : { text: `Preset ${x + 1}`, note: { sharp: true, on: false } };
  }
  return { text: "" };
}

function ApcMap({ state, notes }: { state: SynthState; notes: number[] }) {
  return (
    <div className="flex flex-wrap items-start gap-7">
      <div>
        <div className="mb-2.5 grid grid-cols-8 gap-1.5">
          {state.knobs.map((k) => (
            <div key={k.name} className="w-16 text-center text-[10px] leading-tight text-muted">
              <div
                className="mx-auto mb-1 h-6.5 w-6.5 rounded-full border-3 border-white/15 border-t-accent"
                style={{ transform: `rotate(${-135 + 270 * k.value}deg)` }}
              />
              <b className="block text-[11px] text-text">{k.knob}. {k.label.replace(" (coupure)", "")}</b>
              {k.display}
            </div>
          ))}
        </div>
        <div className="grid grid-cols-8 gap-1.5">
          {Array.from({ length: 40 }, (_, i) => {
            const pad = padInfo(state, notes, i % 8, Math.floor(i / 8));
            // Pad inactif : fond atténué (35 %), texte toujours lisible.
            const style = pad.note || !pad.color
              ? undefined
              : pad.dim
                ? { background: `${pad.color}59`, color: "#d8d4dc" }
                : { background: pad.color, color: textOn(pad.color) };
            return (
              <div
                key={i}
                style={style}
                className={cx(
                  "flex h-11.5 w-16 items-center justify-center rounded-md border border-line p-0.5 text-center text-[10px] leading-tight font-semibold",
                  !pad.color && !pad.note && "bg-pad-off",
                  pad.note && (pad.note.on ? "bg-[#ffe600] text-[#111]" : pad.note.sharp ? "bg-[#1c1b1f] text-muted" : "bg-[#46444c] text-[#d8d4dc]"),
                )}
              >
                {pad.text}
              </div>
            );
          })}
        </div>
      </div>
      <ul className="m-0 flex max-w-[380px] flex-col gap-2 pl-4.5 text-[13px] text-muted [&_b]:text-text">
        <li><b>Clavier</b> : joue la note de chaque touche, une octave au-dessus de la note MIDI reçue. Pour changer d'octave : les boutons <b>Octave +/-</b> du clavier de l'APC.</li>
        <li><b>Potars 1 à 8</b> : les réglages ci-dessus, dans l'ordre (tourner = ajuster).</li>
        <li><b>Rangée 1</b> : forme d'onde (5 pads à gauche), puis coupure douce (orange) et coupure nette (rouge).</li>
        <li><b>Rangée 3</b> : Do Ré Mi Fa Sol La Si ; <b>rangée 2</b> : les dièses juste au-dessus, comme les touches noires d'un piano. Les notes jouées s'allument en jaune.</li>
        <li><b>Rangée 4</b> : arpège, mode, vitesse, latch, accords, mono + glissando, écho, vibrato (vif = actif ; mode, vitesse et accords font défiler les choix).</li>
        <li><b>Rangée 5</b> : 8 presets. Appui = charger, <b>Shift</b> + appui = enregistrer le son actuel.</li>
        <li><b>Ligne rouge</b> (boutons 1 à 8 sous les pads) : les 8 fichiers MIDI. Appui = lecture / arrêt ; LED allumée = morceau chargé, clignotante = en lecture. Les notes du morceau s'allument en jaune comme celles du clavier.</li>
        <li><b>Sustain</b> : retour au menu (le synthé se tait) · <b>Play</b> : pause. Ces touches appartiennent au gestionnaire.</li>
      </ul>
    </div>
  );
}
