import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { Button, cx } from "@apcdeck/web";
import type { Slot } from "./types";

const W = 800;
const H = 100;

const formatTime = (s: number) => `${Math.floor(s / 60)}:${(s % 60).toFixed(2).padStart(5, "0")}`;

/**
 * Plage jouée d'un son : forme d'onde (calculée par le plugin), poignées de début et de fin à glisser, champs
 * numériques. [end] = 0 : jusqu'à la fin du fichier. Pendant la lecture, un curseur suit la position estimée.
 */
export function Trim({
  slot,
  soundId,
  start,
  end,
  playing,
  paused,
  color,
  onChange,
}: {
  slot: Slot;
  soundId: string;
  start: number;
  end: number;
  playing: boolean;
  paused: boolean;
  color: string;
  onChange: (range: { start: number; end: number }, delay: number) => void;
}) {
  const [wave, setWave] = useState<{ peaks: number[]; duration: number } | null>(null);
  const [dragging, setDragging] = useState<"start" | "end" | null>(null);
  const svg = useRef<SVGSVGElement>(null);
  const cursor = usePlayhead(playing, paused, start);

  useEffect(() => {
    setWave(null);
    void window.apcdeck?.call<{ peaks: number[]; duration: number } | null>("peaks", slot).then((w) => w && setWave(w));
  }, [soundId]);

  if (!wave) return <div className="flex h-24 items-center justify-center rounded-lg bg-[#0d0c10] text-xs text-muted">Forme d'onde…</div>;

  const duration = wave.duration;
  const to = end > 0 ? Math.min(end, duration) : duration;
  const x = (t: number) => (t / duration) * W;
  const round = (t: number) => Math.round(t * 100) / 100;
  const set = (s: number, e: number, delay: number) => {
    const a = Math.max(0, Math.min(round(s), duration));
    const b = Math.max(0, Math.min(round(e), duration));
    if (b - a < 0.05) return;
    onChange({ start: a, end: b >= duration ? 0 : b }, delay);
  };

  const timeAt = (e: ReactPointerEvent) => {
    const r = svg.current!.getBoundingClientRect();
    return Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)) * duration;
  };
  const down = (e: ReactPointerEvent<SVGSVGElement>) => {
    const t = timeAt(e);
    const handle = Math.abs(t - start) <= Math.abs(t - to) ? "start" : "end";
    e.currentTarget.setPointerCapture(e.pointerId);
    setDragging(handle);
    if (handle === "start") set(t, to, 300);
    else set(start, t, 300);
  };
  const move = (e: ReactPointerEvent) => {
    if (!dragging) return;
    const t = timeAt(e);
    if (dragging === "start") set(Math.min(t, to - 0.05), to, 300);
    else set(start, Math.max(t, start + 0.05), 300);
  };

  const bars = wave.peaks.map((p, i) => `M${(i + 0.5) * (W / wave.peaks.length)} ${H / 2 - (p * H) / 2}v${Math.max(1, p * H)}`).join("");
  const elapsed = cursor !== null ? start + cursor : null;
  const field = "w-full rounded-lg border border-[#938f99] bg-surface px-2 py-1.5 text-text tabular-nums focus:border-transparent focus:outline-2 focus:outline-accent";

  return (
    <div className="flex flex-col gap-2">
      <svg
        ref={svg}
        viewBox={`0 0 ${W} ${H}`}
        preserveAspectRatio="none"
        className={cx("h-24 w-full touch-none rounded-lg bg-[#0d0c10]", dragging ? "cursor-grabbing" : "cursor-ew-resize")}
        onPointerDown={down}
        onPointerMove={move}
        onPointerUp={() => setDragging(null)}
      >
        <path d={bars} stroke={color} strokeWidth={W / wave.peaks.length} vectorEffect="non-scaling-stroke" />
        <rect x={0} y={0} width={x(start)} height={H} fill="#000" opacity={0.65} />
        <rect x={x(to)} y={0} width={W - x(to)} height={H} fill="#000" opacity={0.65} />
        <line x1={x(start)} x2={x(start)} y1={0} y2={H} stroke="#fff" strokeWidth={2} vectorEffect="non-scaling-stroke" />
        <line x1={x(to)} x2={x(to)} y1={0} y2={H} stroke="#fff" strokeWidth={2} vectorEffect="non-scaling-stroke" />
        {elapsed !== null && elapsed <= to && (
          <line x1={x(elapsed)} x2={x(elapsed)} y1={0} y2={H} stroke="var(--color-ok)" strokeWidth={2} vectorEffect="non-scaling-stroke" />
        )}
      </svg>
      <div className="grid grid-cols-[1fr_1fr_auto] items-end gap-2 text-[13px]">
        <label className="flex flex-col gap-1">
          Début (s)
          <input className={field} type="number" min={0} max={duration} step={0.01} value={start} onChange={(e) => set(Number(e.target.value), to, 600)} />
        </label>
        <label className="flex flex-col gap-1">
          Fin (s)
          <input className={field} type="number" min={0} max={duration} step={0.01} value={round(to)} onChange={(e) => set(start, Number(e.target.value), 600)} />
        </label>
        <Button size="sm" disabled={start === 0 && end === 0} onClick={() => onChange({ start: 0, end: 0 }, 0)} title="Jouer le fichier entier">
          Tout
        </Button>
      </div>
      <span className="text-xs text-muted">
        Joué : {formatTime(start)} → {formatTime(to)} ({(to - start).toFixed(2)} s sur {duration.toFixed(2)} s) · glisse les poignées sur la forme d'onde
      </span>
    </div>
  );
}

/** Secondes jouées depuis le début de la lecture, pauses exclues (estimées côté page), ou null à l'arrêt. */
function usePlayhead(playing: boolean, paused: boolean, start: number): number | null {
  const [elapsed, setElapsed] = useState<number | null>(null);
  const before = useRef(0); // temps joué avant la dernière pause

  useEffect(() => {
    before.current = 0;
    setElapsed(playing ? 0 : null);
  }, [playing, start]);

  useEffect(() => {
    if (!playing || paused) return;
    const t0 = performance.now();
    const base = before.current;
    let frame = requestAnimationFrame(function tick() {
      before.current = base + (performance.now() - t0) / 1000;
      setElapsed(before.current);
      frame = requestAnimationFrame(tick);
    });
    return () => cancelAnimationFrame(frame);
  }, [playing, paused, start]);

  return elapsed;
}
