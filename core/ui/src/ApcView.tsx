import { useEffect, useRef } from "react";
import { Piano, cx, padHex } from "@apcdeck/web";
import { cmd, pressHandlers } from "./api";
import type { AppState, LogLine } from "./types";

const BRIGHTNESS = [0.1, 0.25, 0.5, 0.65, 0.75, 0.9, 1];

/** Animation et opacité d'une LED selon le canal MIDI : 0-6 luminosité, 7-10 pulse, 11-15 clignotement. */
function ledStyle(channel: number): { className: string; opacity?: number } {
  if (channel >= 11) return { className: "animate-blink-led" };
  if (channel >= 7) return { className: "animate-pulse-led" };
  return { className: "", opacity: BRIGHTNESS[channel] ?? 1 };
}

const simulate = (data: Record<string, unknown>) => cmd("simulate", data);

/** Vue matérielle : potars, pads, boutons (miroir cliquable de l'APC), clavier et journal. */
export function ApcView({ state }: { state: AppState }) {
  const leds = state.leds;
  const input = state.input ?? { pads: [], buttons: [], notes: [], knobs: Array(8).fill(64) };
  const pressedPads = new Set(input.pads);
  const pressedButtons = new Set(input.buttons);

  return (
    <div className="flex min-h-full flex-col gap-3.5 p-4">
      <h3 className="m-0 text-sm font-semibold">APC Key 25 mk2</h3>
      <div className="flex gap-1.5">
        {input.knobs.map((value, i) => <Knob key={i} index={i} value={value} />)}
      </div>
      <div className="flex gap-3.5">
        <div className="grid grid-cols-[repeat(8,46px)] gap-1.5">
          {Array.from({ length: 40 }, (_, i) => {
            const color = leds?.colors[i] ?? 0;
            const led = ledStyle(leds?.effects[i] ?? 6);
            return (
              <div
                key={i}
                data-x={i % 8}
                data-y={Math.floor(i / 8)}
                {...pressHandlers((pressed) => simulate({ type: "pad", x: i % 8, y: Math.floor(i / 8), pressed }))}
                className={cx("pad relative h-[46px] w-[46px] cursor-pointer rounded-md border border-line bg-pad-off", pressedPads.has(i) && "pressed z-10 outline-3 -outline-offset-1 outline-accent")}
              >
                {color > 0 && <div className={cx("absolute inset-0 rounded-[inherit]", led.className)} style={{ background: padHex(color), opacity: led.opacity }} />}
              </div>
            );
          })}
        </div>
        <div className="grid grid-rows-[repeat(5,46px)] gap-1.5">
          {[1, 2, 3, 4, 5].map((s) => (
            <div key={s} className="flex h-[46px] w-[46px] items-center justify-center">
              <ButtonLed name={`SCENE_${s}`} title={`Scène ${s}`} rgb="#2ee65a" round state={leds?.buttons[`SCENE_${s}`] ?? 0} pressed={pressedButtons.has(`SCENE_${s}`)} />
            </div>
          ))}
        </div>
      </div>
      <div className="grid grid-cols-[repeat(8,46px)] gap-1.5">
        {[1, 2, 3, 4, 5, 6, 7, 8].map((t) => (
          <ButtonLed key={t} name={`TRACK_${t}`} title={`Track ${t}`} rgb="#ff3b30" state={leds?.buttons[`TRACK_${t}`] ?? 0} pressed={pressedButtons.has(`TRACK_${t}`)} />
        ))}
      </div>
      <div className="flex items-center gap-2 text-xs">
        <span className="text-muted">Touches system (pager) :</span>
        {["SHIFT", "SUSTAIN", "PLAY", "REC"].map((b) => <SysButton key={b} name={b} pressed={pressedButtons.has(b)} />)}
        <span className="ml-3 text-muted">· Autre :</span>
        <SysButton name="STOP_ALL" pressed={pressedButtons.has("STOP_ALL")} />
      </div>
      <h3 className="m-0 text-sm font-semibold">Clavier</h3>
      <Piano low={0} high={120} pressed={new Set(input.notes)} onPress={(note, pressed) => simulate({ type: "key", note, pressed })} />
      <h3 className="m-0 text-sm font-semibold">Journal</h3>
      <Logs lines={state.logs} />
    </div>
  );
}

function ButtonLed({ name, title, rgb, state, pressed, round }: { name: string; title: string; rgb: string; state: number; pressed: boolean; round?: boolean }) {
  return (
    <div
      title={title}
      {...pressHandlers((down) => simulate({ type: "button", button: name, pressed: down }))}
      className={cx(
        "relative cursor-pointer border border-line",
        round ? "h-[26px] w-[26px] rounded-full" : "h-3.5 rounded-[3px]",
        pressed && "z-10 outline-3 -outline-offset-1 outline-accent",
      )}
      style={{ background: `${rgb}1f` }}
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

/** Potar : la molette de la souris le tourne (événements simulés). */
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
  return (
    <div ref={ref} title="Molette pour tourner" className="knob w-[46px] cursor-ns-resize text-center text-[11px] text-muted">
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
