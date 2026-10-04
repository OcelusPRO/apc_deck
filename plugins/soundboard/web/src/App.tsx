import { useCallback, useEffect, useRef, useState, type DragEvent, type MouseEvent as ReactMouseEvent } from "react";
import { Banner, Button, Slider, cx, padHex, textOn, usePluginState } from "@apcdeck/web";
import { uploadSound } from "./audio";
import { SoundEditor } from "./SoundEditor";
import { MAX_VOLUME, sameSlot, slotKey, type Slot, type SoundboardState } from "./types";

const COLS = 8;
const ROWS = 5;
const PLAYING_ANIM = "animate-[pulse-led_0.7s_ease-in-out_infinite]";

type Call = (action: string, data?: unknown) => Promise<SoundboardState | null>;
type Uploads = Record<string, number>;

const hasFiles = (e: DragEvent) => e.dataTransfer.types.includes("Files");

export function App() {
  const { state, call, error } = usePluginState<SoundboardState>();
  const [selected, setSelected] = useState<Slot | null>(null);
  const [uploads, setUploads] = useState<Uploads>({});
  const [uploadError, setUploadError] = useState<string | null>(null);

  /** Convertit et envoie un fichier audio sur un pad (le son est créé s'il n'existe pas). */
  const upload = useCallback(
    async (file: File, slot: Slot) => {
      const key = slotKey(slot);
      setUploadError(null);
      try {
        await uploadSound(file, slot, call, (p) => setUploads((u) => ({ ...u, [key]: p })));
      } catch (e) {
        setUploadError(e instanceof Error ? e.message : String(e));
      } finally {
        setUploads(({ [key]: _, ...rest }) => rest);
      }
    },
    [call],
  );

  if (!state) return <div className="p-6 text-muted">{error ?? "Chargement…"}</div>;
  const sound = selected && state.sounds.find((s) => sameSlot(s, selected));
  const anyPlaying = state.sounds.some((s) => s.playing);

  return (
    <div className="mx-auto flex h-screen max-w-[1100px] flex-col gap-4 overflow-auto p-5">
      {(uploadError ?? error) && <Banner tone="error">{uploadError ?? error}</Banner>}
      <div className="flex items-center gap-4">
        <MasterVolume value={state.master} knob={state.masterKnob} call={call} />
        <Button variant={anyPlaying ? "stop" : "outline"} disabled={!anyPlaying} onClick={() => call("stopAll")}>
          ■ Tout arrêter
        </Button>
      </div>
      <Board state={state} call={call} selected={selected} onSelect={setSelected} uploads={uploads} upload={upload} />
      {selected && (
        <SoundEditor
          key={sound?.id ?? slotKey(selected)}
          slot={selected}
          sound={sound ?? null}
          call={call}
          progress={uploads[slotKey(selected)]}
          upload={upload}
          onClose={() => setSelected(null)}
        />
      )}
    </div>
  );
}

// --- volume général ------------------------------------------------------------------------

/** Curseur du volume général (0..200 %), aussi réglé au potar ; s'applique aux sons en cours. */
function MasterVolume({ value, knob, call }: { value: number; knob: string; call: Call }) {
  const [local, setLocal] = useState(value);
  const timer = useRef<number | undefined>(undefined);
  const editing = useRef(false);

  useEffect(() => {
    if (!editing.current) setLocal(value); // potar, autre page ouverte…
  }, [value]);

  return (
    <div className="flex flex-1 items-center gap-4 rounded-xl bg-surface-2 px-3.5 py-2.5">
      <div className="flex-1">
        <Slider
          label={<>Volume général <span className="text-xs text-muted">· potar : {knob}</span></>}
          display={`${local} %`}
          value={local}
          min={0}
          max={MAX_VOLUME}
          step={1}
          onChange={(v) => {
            setLocal(v);
            editing.current = true;
            window.clearTimeout(timer.current);
            timer.current = window.setTimeout(async () => {
              await call("master", { volume: v });
              editing.current = false;
            }, 60);
          }}
        />
      </div>
    </div>
  );
}

// --- pages et pads --------------------------------------------------------------------------

function Board({
  state,
  call,
  selected,
  onSelect,
  uploads,
  upload,
}: {
  state: SoundboardState;
  call: Call;
  selected: Slot | null;
  onSelect: (slot: Slot) => void;
  uploads: Uploads;
  upload: (file: File, slot: Slot) => void;
}) {
  const { current } = state;
  const [fileTarget, setFileTarget] = useState<Slot | null>(null);
  const drag = usePadDrag(call, onSelect);
  const counts = new Map<string, { sounds: number; playing: boolean }>();
  state.sounds.forEach((s) => {
    const key = `${s.row}.${s.col}`;
    const c = counts.get(key) ?? { sounds: 0, playing: false };
    counts.set(key, { sounds: c.sounds + 1, playing: c.playing || s.playing });
  });

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-baseline gap-3">
        <h2 className="m-0 text-base font-semibold">Page {current.row + 1}·{current.col + 1}</h2>
        <span className="text-xs text-muted">colonne verte × ligne rouge · Stop All coupe tous les sons</span>
      </div>
      <div className="grid w-fit grid-cols-8 gap-[3px]">
        {Array.from({ length: 40 }, (_, i) => {
          const row = Math.floor(i / 8);
          const col = i % 8;
          const isCurrent = row === current.row && col === current.col;
          const count = counts.get(`${row}.${col}`);
          return (
            <button
              key={i}
              type="button"
              title={`Page ${row + 1}·${col + 1}`}
              onClick={() => call("page", { row, col })}
              className={cx(
                "h-5 w-7.5 cursor-pointer rounded-[3px] text-[10px]",
                isCurrent ? "bg-accent font-semibold text-on-accent" : count ? "bg-[#4a4458]" : "bg-white/5",
                count?.playing && !isCurrent && "outline-1 outline-ok",
              )}
            >
              {count?.sounds || ""}
            </button>
          );
        })}
      </div>
      <div className="grid grid-cols-8 gap-2.5">
        {Array.from({ length: COLS * ROWS }, (_, i) => {
          const slot: Slot = { ...current, x: i % COLS, y: Math.floor(i / COLS) };
          const sound = state.sounds.find((s) => sameSlot(s, slot));
          const color = sound ? padHex(sound.color) : undefined;
          const progress = uploads[slotKey(slot)];
          return (
            <div
              key={i}
              data-slot={JSON.stringify(slot)}
              onMouseDown={(e) => drag.start(e, slot, !!sound)}
              onDragOver={(e) => {
                if (!hasFiles(e)) return;
                e.preventDefault();
                setFileTarget(slot);
              }}
              onDragLeave={() => setFileTarget(null)}
              onDrop={(e) => {
                e.preventDefault();
                setFileTarget(null);
                const file = e.dataTransfer.files[0];
                if (file) void upload(file, slot);
              }}
              className={cx(
                "group relative flex aspect-square cursor-pointer items-center justify-center overflow-hidden rounded-xl border border-line p-2 text-center text-[13px] font-semibold select-none",
                !sound && "bg-pad-off text-muted",
                sound?.playing && (sound.paused ? "animate-blink-led" : PLAYING_ANIM),
                selected && sameSlot(selected, slot) && "outline-2 outline-white",
                ((drag.target && sameSlot(drag.target, slot)) || (fileTarget && sameSlot(fileTarget, slot))) && "outline-3 outline-accent",
              )}
              style={color ? { background: color, color: textOn(color) } : undefined}
              title={sound ? `${sound.name}${sound.fileName ? ` — ${sound.fileName}` : ""}` : "Pad vide"}
            >
              {sound ? sound.name : <span className="opacity-0 group-hover:opacity-60">+</span>}
              {sound && !sound.hasFile && <span className="absolute inset-x-0 bottom-1 text-[9px]">⚠ aucun fichier</span>}
              {sound?.playing && (
                <span className="absolute inset-x-0 bottom-1 text-[9px]">{sound.paused ? "⏸ en pause" : "▶ en cours"}</span>
              )}
              {sound?.hasFile && (
                <span className="absolute top-0.5 right-1.5 flex gap-1.5 text-[11px] opacity-0 group-hover:opacity-80">
                  {sound.playing && (
                    <span title="Arrêter" onMouseDown={(e) => e.stopPropagation()} onClick={() => call("stop", slot)}>
                      ■
                    </span>
                  )}
                  <span
                    title={!sound.playing ? "Jouer" : sound.paused ? "Reprendre" : "Pause"}
                    onMouseDown={(e) => e.stopPropagation()}
                    onClick={() => call("play", slot)}
                  >
                    {sound.playing && !sound.paused ? "⏸" : "▶"}
                  </span>
                </span>
              )}
              {progress !== undefined && (
                <span className="absolute inset-0 flex items-center justify-center bg-black/60 text-[10px] text-white">
                  {progress === 0 ? "Conversion…" : `${Math.round(progress * 100)} %`}
                </span>
              )}
            </div>
          );
        })}
      </div>
      <p className="text-xs text-muted">
        Clic = ouvrir l'éditeur du pad · glisse un pad pour le déplacer (échange si occupé) · glisse un fichier audio sur un pad pour lui
        donner ce son. Sur l'APC : appui = lecture, puis appui court = pause / reprise, appui long = arrêt.
      </p>
    </div>
  );
}

/** Glisser un pad vers un autre (échange si occupé) ; un simple clic l'ouvre dans l'éditeur. */
function usePadDrag(call: Call, onSelect: (slot: Slot) => void) {
  const [drag, setDrag] = useState<{ from: Slot; movable: boolean; x: number; y: number; moved: boolean } | null>(null);
  const [target, setTarget] = useState<Slot | null>(null);

  const slotAt = (x: number, y: number): Slot | null => {
    const el = document.elementFromPoint(x, y)?.closest<HTMLElement>("[data-slot]");
    return el ? (JSON.parse(el.dataset.slot ?? "null") as Slot) : null;
  };

  useEffect(() => {
    if (!drag) return;
    const move = (e: MouseEvent) => {
      if (!drag.movable) return;
      if (!drag.moved && Math.hypot(e.clientX - drag.x, e.clientY - drag.y) > 4) setDrag({ ...drag, moved: true });
      setTarget(slotAt(e.clientX, e.clientY));
    };
    const up = (e: MouseEvent) => {
      setDrag(null);
      setTarget(null);
      if (!drag.moved) return onSelect(drag.from);
      const to = slotAt(e.clientX, e.clientY);
      if (to && !sameSlot(to, drag.from)) void call("move", { from: drag.from, to });
    };
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
    return () => {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
    };
  }, [drag, call, onSelect]);

  return {
    start: (e: ReactMouseEvent, from: Slot, movable: boolean) => {
      if (e.button !== 0) return;
      e.preventDefault();
      setDrag({ from, movable, x: e.clientX, y: e.clientY, moved: false });
    },
    target: drag?.moved ? target : null,
  };
}
