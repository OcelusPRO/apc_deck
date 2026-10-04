import { useEffect, useRef, useState, type CSSProperties } from "react";
import { Button, Piano, cx, noteName, padHex, pressHandlers } from "@apcdeck/web";
import { cmd } from "./api";
import type { AppState, LogLine } from "./types";

const BRIGHTNESS = [0.1, 0.25, 0.5, 0.65, 0.75, 0.9, 1];

/** Animation et opacité d'une LED selon le canal MIDI : 0-6 luminosité, 7-10 pulse, 11-15 clignotement. */
function ledStyle(channel: number): { className: string; opacity?: number } {
  if (channel >= 11) return { className: "animate-blink-led" };
  if (channel >= 7) return { className: "animate-pulse-led" };
  return { className: "", opacity: BRIGHTNESS[channel] ?? 1 };
}

const simulate = (data: Record<string, unknown>) => cmd("simulate", data);

/**
 * Vue matérielle : potars, pads, boutons (miroir cliquable de l'APC), clavier et journal.
 * [virtual] : l'APC virtuel remplace l'appareil absent : vue agrandie (tactile), clavier de 25 touches avec octaves
 * et jouable au clavier de l'ordinateur.
 */
export function ApcView({ state, virtual = false, remoteServer = null }: { state: AppState; virtual?: boolean; remoteServer?: string | null }) {
  const leds = state.leds;
  const input = state.input ?? { pads: [], buttons: [], notes: [], knobs: Array(8).fill(64) };
  const pressedPads = new Set(input.pads);
  const pressedButtons = new Set(input.buttons);
  // Taille d'un pad : fixe pour le miroir ; à la largeur disponible (8 pads + colonne des scènes) en virtuel.
  const size: CSSProperties = { ["--pad" as string]: virtual ? "clamp(26px, calc((100cqi - 8 * 6px - 14px) / 9), 96px)" : "46px" };

  return (
    <div className={cx("flex min-h-full flex-col gap-3.5 p-4", virtual && "@container select-none")} style={size}>
      <div className="flex items-center gap-3">
        <h3 className="m-0 text-sm font-semibold whitespace-nowrap">{remoteServer ? `APC de ${remoteServer}` : virtual ? "APC virtuel" : "APC Key 25 mk2"}</h3>
        {remoteServer ? (
          <span className="text-xs text-muted">
            Cet appareil pilote {remoteServer} : ce qui est joué ici (écran ou APC branché) part vers lui, ses LED s'affichent ici.
          </span>
        ) : virtual && (
          <span className="text-xs text-muted">
            Aucun APC branché : cette vue le remplace (souris, tactile, clavier de l'ordinateur). Un APC réel branché reprend la main.
          </span>
        )}
      </div>
      <div className="flex gap-1.5">
        {input.knobs.map((value, i) => <Knob key={i} index={i} value={value} />)}
      </div>
      <div className="flex gap-3.5">
        <div className="grid grid-cols-[repeat(8,var(--pad))] gap-1.5">
          {Array.from({ length: 40 }, (_, i) => {
            const color = leds?.colors[i] ?? 0;
            const led = ledStyle(leds?.effects[i] ?? 6);
            return (
              <div
                key={i}
                data-x={i % 8}
                data-y={Math.floor(i / 8)}
                {...pressHandlers((pressed) => simulate({ type: "pad", x: i % 8, y: Math.floor(i / 8), pressed }))}
                className={cx("pad relative h-(--pad) w-(--pad) cursor-pointer rounded-md border border-line bg-pad-off", pressedPads.has(i) && "pressed z-10 outline-3 -outline-offset-1 outline-accent")}
              >
                {color > 0 && <div className={cx("absolute inset-0 rounded-[inherit]", led.className)} style={{ background: padHex(color), opacity: led.opacity }} />}
              </div>
            );
          })}
        </div>
        <div className="grid grid-rows-[repeat(5,var(--pad))] gap-1.5">
          {[1, 2, 3, 4, 5].map((s) => (
            <div key={s} className="flex h-(--pad) w-(--pad) items-center justify-center">
              <ButtonLed name={`SCENE_${s}`} title={`Scène ${s}`} rgb="#2ee65a" round state={leds?.buttons[`SCENE_${s}`] ?? 0} pressed={pressedButtons.has(`SCENE_${s}`)} />
            </div>
          ))}
        </div>
      </div>
      <div className="grid grid-cols-[repeat(8,var(--pad))] gap-1.5">
        {[1, 2, 3, 4, 5, 6, 7, 8].map((t) => (
          <ButtonLed key={t} name={`TRACK_${t}`} title={`Track ${t}`} rgb="#ff3b30" state={leds?.buttons[`TRACK_${t}`] ?? 0} pressed={pressedButtons.has(`TRACK_${t}`)} />
        ))}
      </div>
      <div className="flex flex-wrap items-center gap-2 text-xs">
        <span className="text-muted">Touches system (pager) :</span>
        {["SHIFT", "SUSTAIN", "PLAY", "REC"].map((b) => <SysButton key={b} name={b} pressed={pressedButtons.has(b)} />)}
        <span className="ml-3 text-muted">· Autre :</span>
        <SysButton name="STOP_ALL" pressed={pressedButtons.has("STOP_ALL")} />
      </div>
      {virtual ? (
        <VirtualKeyboard notes={input.notes} />
      ) : (
        <>
          <h3 className="m-0 text-sm font-semibold">Clavier</h3>
          <Piano low={0} high={120} pressed={new Set(input.notes)} onPress={(note, pressed) => simulate({ type: "key", note, pressed })} />
        </>
      )}
      {virtual ? (
        <details className="flex flex-1 flex-col">
          <summary className="cursor-pointer text-sm font-semibold">Journal</summary>
          <Logs lines={state.logs} />
        </details>
      ) : (
        <>
          <h3 className="m-0 text-sm font-semibold">Journal</h3>
          <Logs lines={state.logs} />
        </>
      )}
    </div>
  );
}

// --- clavier de l'APC virtuel ------------------------------------------------------------------

/** Note de départ du clavier (C3, comme l'APC à l'octave 0) ; 25 touches, comme l'appareil. */
const BASE_NOTE = 48;
const KEYS = 25;
const MIN_OCTAVE = -4;
const MAX_OCTAVE = 4;

/**
 * Touches de l'ordinateur -> demi-tons au-dessus de la note la plus basse. Positions physiques (KeyboardEvent.code) :
 * la rangée du milieu joue les touches blanches et la rangée du dessus les noires, en AZERTY comme en QWERTY.
 */
const COMPUTER_KEYS: Record<string, number> = {
  KeyA: 0, KeyW: 1, KeyS: 2, KeyE: 3, KeyD: 4, KeyF: 5, KeyT: 6, KeyG: 7, KeyY: 8, KeyH: 9, KeyU: 10, KeyJ: 11,
  KeyK: 12, KeyO: 13, KeyL: 14, KeyP: 15, Semicolon: 16, Quote: 17,
};
const OCTAVE_DOWN = "KeyZ";
const OCTAVE_UP = "KeyX";

/** Saisie en cours dans un champ : les touches ne jouent pas de notes. */
function typing(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  return !!el && (el.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(el.tagName));
}

function VirtualKeyboard({ notes }: { notes: number[] }) {
  const [octave, setOctave] = useState(0);
  const low = Math.max(0, Math.min(127 - KEYS + 1, BASE_NOTE + 12 * octave));
  const lowRef = useRef(low);
  lowRef.current = low;

  // Clavier de l'ordinateur : chaque touche tenue garde la note jouée à l'appui (même si l'octave change entre-temps).
  useEffect(() => {
    const held = new Map<string, number>();
    const down = (e: KeyboardEvent) => {
      if (e.repeat || e.ctrlKey || e.metaKey || e.altKey || typing(e.target)) return;
      if (e.code === OCTAVE_DOWN || e.code === OCTAVE_UP) {
        e.preventDefault();
        setOctave((o) => Math.max(MIN_OCTAVE, Math.min(MAX_OCTAVE, o + (e.code === OCTAVE_UP ? 1 : -1))));
        return;
      }
      const offset = COMPUTER_KEYS[e.code];
      if (offset === undefined || held.has(e.code)) return;
      e.preventDefault();
      const note = lowRef.current + offset;
      if (note > 127) return;
      held.set(e.code, note);
      void simulate({ type: "key", note, pressed: true });
    };
    const up = (e: KeyboardEvent) => {
      const note = held.get(e.code);
      if (note === undefined) return;
      held.delete(e.code);
      void simulate({ type: "key", note, pressed: false });
    };
    // Fenêtre quittée touche enfoncée : le relâchement n'arrivera jamais, on relâche tout.
    const releaseAll = () => {
      held.forEach((note) => void simulate({ type: "key", note, pressed: false }));
      held.clear();
    };
    window.addEventListener("keydown", down);
    window.addEventListener("keyup", up);
    window.addEventListener("blur", releaseAll);
    return () => {
      window.removeEventListener("keydown", down);
      window.removeEventListener("keyup", up);
      window.removeEventListener("blur", releaseAll);
      releaseAll();
    };
  }, []);

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="m-0 text-sm font-semibold">Clavier</h3>
        <Button size="sm" disabled={octave <= MIN_OCTAVE} onClick={() => setOctave((o) => o - 1)}>Octave −</Button>
        <span className="w-24 text-center text-xs tabular-nums">
          {noteName(low)} – {noteName(low + KEYS - 1)}
        </span>
        <Button size="sm" disabled={octave >= MAX_OCTAVE} onClick={() => setOctave((o) => o + 1)}>Octave +</Button>
        <span className="text-xs text-muted pointer-coarse:hidden">
          Clavier de l'ordinateur : rangée du milieu (Q S D F… en AZERTY) et rangée du dessus pour les dièses ; W / X pour
          l'octave (Z / X en QWERTY).
        </span>
      </div>
      <Piano low={low} high={low + KEYS - 1} height={140} pressed={new Set(notes)} onPress={(note, pressed) => simulate({ type: "key", note, pressed })} />
    </div>
  );
}

// --- boutons et potars ---------------------------------------------------------------------------

function ButtonLed({ name, title, rgb, state, pressed, round }: { name: string; title: string; rgb: string; state: number; pressed: boolean; round?: boolean }) {
  const press = pressHandlers((down) => simulate({ type: "button", button: name, pressed: down }));
  return (
    <div
      title={title}
      {...press}
      className={cx(
        "relative cursor-pointer border border-line",
        round ? "h-[calc(var(--pad)*0.56)] w-[calc(var(--pad)*0.56)] rounded-full" : "h-[calc(var(--pad)*0.3)] rounded-[3px]",
        pressed && "z-10 outline-3 -outline-offset-1 outline-accent",
      )}
      style={{ ...press.style, background: `${rgb}1f` }}
    >
      {state > 0 && <div className={cx("absolute inset-0 rounded-[inherit]", state === 2 && "animate-blink-led")} style={{ background: rgb }} />}
    </div>
  );
}

function SysButton({ name, pressed }: { name: string; pressed: boolean }) {
  return (
    <div
      {...pressHandlers((down) => simulate({ type: "button", button: name, pressed: down }))}
      className={cx("sys cursor-pointer rounded border border-line px-2.5 py-1 select-none", pressed ? "pressed bg-accent/55 outline-3 -outline-offset-1 outline-accent" : "bg-[#3a3a40]")}
    >
      {name.toLowerCase().replace("_", " ")}
    </div>
  );
}

/** Arc de 270° sur un cercle de rayon 15 centré en (17, 17). */
function arc(sweep: number): string {
  if (sweep <= 0) return "";
  const r = 15;
  const a0 = (135 * Math.PI) / 180;
  const a1 = ((135 + Math.min(sweep, 269.9)) * Math.PI) / 180;
  const p = (a: number) => `${(17 + r * Math.cos(a)).toFixed(2)} ${(17 + r * Math.sin(a)).toFixed(2)}`;
  return `M ${p(a0)} A ${r} ${r} 0 ${sweep > 180 ? 1 : 0} 1 ${p(a1)}`;
}

/** Pixels de glissement vertical pour un cran de potar. */
const DRAG_STEP = 3;

/** Potar : la molette, ou un glissement vertical (souris ou doigt), le tourne (événements simulés). */
function Knob({ index, value }: { index: number; value: number }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const wheel = (e: WheelEvent) => {
      e.preventDefault();
      void simulate({ type: "knob", index, delta: e.deltaY < 0 ? 1 : -1 });
    };
    el.addEventListener("wheel", wheel, { passive: false });
    return () => el.removeEventListener("wheel", wheel);
  }, [index]);

  const drag = useRef<{ id: number; y: number } | null>(null);
  return (
    <div
      ref={ref}
      title="Molette, ou glisser vers le haut / le bas, pour tourner"
      onPointerDown={(e) => {
        if (e.pointerType === "mouse" && e.button !== 0) return;
        e.preventDefault();
        e.currentTarget.setPointerCapture(e.pointerId);
        drag.current = { id: e.pointerId, y: e.clientY };
      }}
      onPointerMove={(e) => {
        const d = drag.current;
        if (!d || d.id !== e.pointerId) return;
        const steps = Math.trunc((d.y - e.clientY) / DRAG_STEP);
        if (steps === 0) return;
        d.y -= steps * DRAG_STEP;
        void simulate({ type: "knob", index, delta: steps });
      }}
      onPointerUp={() => (drag.current = null)}
      onPointerCancel={() => (drag.current = null)}
      style={{ touchAction: "none" }}
      className="knob w-(--pad) cursor-ns-resize text-center text-[11px] text-muted select-none"
    >
      <svg width="34" height="34" viewBox="0 0 34 34" className="mx-auto mb-0.5 block">
        <path d={arc(270)} stroke="#ffffff1f" strokeWidth="4" fill="none" strokeLinecap="round" />
        <path d={arc((270 * value) / 127)} stroke="var(--color-accent)" strokeWidth="4" fill="none" strokeLinecap="round" />
      </svg>
      {index + 1} · {value}
    </div>
  );
}

const LEVEL_COLOR: Record<LogLine["level"], string> = { DEBUG: "text-[#888]", INFO: "", WARN: "text-warn", ERROR: "text-danger" };

function Logs({ lines }: { lines: LogLine[] }) {
  const ref = useRef<HTMLPreElement>(null);
  const stick = useRef(true);
  useEffect(() => {
    const el = ref.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [lines]);
  return (
    <pre
      ref={ref}
      id="logs"
      onScroll={(e) => {
        const el = e.currentTarget;
        stick.current = el.scrollTop + el.clientHeight >= el.scrollHeight - 20;
      }}
      className="m-0 min-h-36 flex-1 overflow-auto rounded-lg bg-[#111114] px-2.5 py-2 font-mono text-xs whitespace-pre-wrap"
    >
      {lines.map((l, i) => <span key={i} className={LEVEL_COLOR[l.level]}>{l.text + "\n"}</span>)}
    </pre>
  );
}
