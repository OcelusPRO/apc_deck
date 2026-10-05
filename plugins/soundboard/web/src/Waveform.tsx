import { useEffect, useLayoutEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { Button, cx } from "@apcdeck/web";

const MAX_ZOOM = 128;
const HANDLE_PX = 10;
const RULER_STEPS = [0.01, 0.02, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300];

export const formatTime = (s: number, precise = true) => {
  const m = Math.floor(s / 60);
  const sec = s % 60;
  return `${m}:${precise ? sec.toFixed(2).padStart(5, "0") : String(Math.floor(sec)).padStart(2, "0")}`;
};

/**
 * Forme d'onde zoomable (Ctrl + molette, boutons) et défilable (molette), avec les poignées de début et de fin.
 * Clic ailleurs que sur une poignée = écouter à partir de là. [onRange] reçoit `done` au relâchement d'une poignée.
 */
export function Waveform({
  peaks,
  duration,
  start,
  end,
  color,
  playhead,
  onRange,
  onSeek,
}: {
  peaks: number[];
  duration: number;
  start: number;
  /** Fin effective (secondes), jamais 0 ici. */
  end: number;
  color: string;
  playhead: number | null;
  onRange: (start: number, end: number, done: { handle: "start" | "end" } | null) => void;
  onSeek: (t: number) => void;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(800);
  const [zoom, setZoom] = useState(1);
  const [dragging, setDragging] = useState<"start" | "end" | null>(null);
  const anchor = useRef<{ t: number; offset: number } | null>(null); // point à garder sous la souris au zoom

  const inner = Math.floor(width * zoom);
  const px = (t: number) => (t / duration) * inner;
  const n = peaks.length;

  useLayoutEffect(() => {
    const el = scroller.current!;
    const observer = new ResizeObserver(() => setWidth(el.clientWidth));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // Après un changement de zoom, garde le point d'ancrage au même endroit de l'écran.
  useLayoutEffect(() => {
    const el = scroller.current!;
    if (anchor.current) el.scrollLeft = px(anchor.current.t) - anchor.current.offset;
    anchor.current = null;
  }, [zoom]);

  const zoomTo = (next: number, t: number, offset: number) => {
    const z = Math.max(1, Math.min(MAX_ZOOM, next));
    anchor.current = { t, offset };
    setZoom(z);
  };
  const viewCenter = () => {
    const el = scroller.current!;
    return { t: ((el.scrollLeft + el.clientWidth / 2) / inner) * duration, offset: el.clientWidth / 2 };
  };

  // Molette : Ctrl = zoom autour de la souris, sinon défilement horizontal. Écouteur non passif (preventDefault).
  useEffect(() => {
    const el = scroller.current!;
    const wheel = (e: WheelEvent) => {
      const rect = el.getBoundingClientRect();
      const offset = e.clientX - rect.left;
      if (e.ctrlKey || e.metaKey) {
        e.preventDefault();
        zoomTo(zoom * (e.deltaY < 0 ? 1.25 : 0.8), ((el.scrollLeft + offset) / inner) * duration, offset);
      } else if (zoom > 1 && Math.abs(e.deltaY) > Math.abs(e.deltaX)) {
        e.preventDefault();
        el.scrollLeft += e.deltaY;
      }
    };
    el.addEventListener("wheel", wheel, { passive: false });
    return () => el.removeEventListener("wheel", wheel);
  });

  // Le curseur de lecture reste visible quand on est zoomé.
  useEffect(() => {
    const el = scroller.current;
    if (playhead === null || !el || dragging) return;
    const x = px(playhead);
    if (x < el.scrollLeft || x > el.scrollLeft + el.clientWidth - 20) el.scrollLeft = x - 40;
  }, [playhead]);

  const timeAt = (e: ReactPointerEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    return Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width)) * duration;
  };
  const down = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    const t = timeAt(e);
    const x = px(t);
    const handle = Math.abs(x - px(start)) <= HANDLE_PX ? "start" : Math.abs(x - px(end)) <= HANDLE_PX ? "end" : null;
    if (!handle) return onSeek(t);
    e.currentTarget.setPointerCapture(e.pointerId);
    setDragging(handle);
  };
  const move = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (!dragging) return;
    const t = timeAt(e);
    const min = 0.05;
    if (dragging === "start") onRange(Math.min(t, end - min), end, null);
    else onRange(start, Math.max(t, start + min), null);
  };
  const up = () => {
    if (dragging) onRange(start, end, { handle: dragging });
    setDragging(null);
  };
  const hover = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (dragging) return;
    const x = px(timeAt(e));
    const near = Math.abs(x - px(start)) <= HANDLE_PX || Math.abs(x - px(end)) <= HANDLE_PX;
    e.currentTarget.style.cursor = near ? "ew-resize" : "pointer";
  };

  // Graduations : au moins ~90 px entre deux étiquettes.
  const step = RULER_STEPS.find((s) => (s / duration) * inner >= 90) ?? 600;
  const ticks = Array.from({ length: Math.floor(duration / step) + 1 }, (_, i) => i * step).filter((t) => px(t) < inner - 40);
  const bars = peaks.map((p, i) => `M${i + 0.5} ${50 - p * 50}v${Math.max(0.5, p * 100)}`).join("");

  const fitSelection = () => {
    const el = scroller.current!;
    const z = Math.max(1, Math.min(MAX_ZOOM, (duration / Math.max(0.05, end - start)) * 0.9));
    anchor.current = { t: start, offset: el.clientWidth * 0.05 };
    setZoom(z);
  };

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center gap-1.5 text-xs text-muted">
        <span>Zoom ×{zoom < 10 ? zoom.toFixed(1) : Math.round(zoom)}</span>
        <Button size="sm" onClick={() => { const c = viewCenter(); zoomTo(zoom / 2, c.t, c.offset); }} disabled={zoom <= 1}>−</Button>
        <Button size="sm" onClick={() => { const c = viewCenter(); zoomTo(zoom * 2, c.t, c.offset); }} disabled={zoom >= MAX_ZOOM}>+</Button>
        <Button size="sm" onClick={fitSelection}>Voir la sélection</Button>
        <Button size="sm" onClick={() => zoomTo(1, 0, 0)} disabled={zoom === 1}>Tout voir</Button>
        <span className="ml-auto">Ctrl + molette : zoom · molette : défiler · glisse les poignées blanches · clic : écouter à partir d'ici</span>
      </div>
      <div ref={scroller} className="overflow-x-auto overflow-y-hidden rounded-lg bg-[#0d0c10]">
        <div
          className="relative select-none"
          style={{ width: inner }}
          onPointerDown={down}
          onPointerMove={(e) => (dragging ? move(e) : hover(e))}
          onPointerUp={up}
          onPointerCancel={up}
        >
          <div className="relative h-6 border-b border-line">
            {ticks.map((t) => (
              <span key={t} className="absolute top-0 h-full border-l border-white/20 pl-1 text-[10px] text-muted tabular-nums" style={{ left: px(t) }}>
                {formatTime(t, step < 1)}
              </span>
            ))}
          </div>
          <svg viewBox={`0 0 ${n} 100`} preserveAspectRatio="none" className="block h-56 w-full">
            <line x1={0} x2={n} y1={50} y2={50} stroke="#ffffff22" strokeWidth={1} vectorEffect="non-scaling-stroke" />
            <path d={bars} stroke={color} strokeWidth={Math.max(1, inner / n - 0.5)} vectorEffect="non-scaling-stroke" />
          </svg>
          {/* Parties ignorées (supprimées à l'enregistrement) */}
          <div className="pointer-events-none absolute top-6 bottom-0 left-0 bg-black/70" style={{ width: px(start) }} />
          <div className="pointer-events-none absolute top-6 right-0 bottom-0 bg-black/70" style={{ left: px(end) }} />
          {(["start", "end"] as const).map((h) => (
            <div key={h} className="pointer-events-none absolute top-0 bottom-0 w-0.5 bg-white" style={{ left: px(h === "start" ? start : end) - 1 }}>
              <span
                className={cx(
                  "absolute top-6 rounded-sm bg-white px-1 text-[10px] font-semibold text-black tabular-nums whitespace-nowrap",
                  h === "start" ? "left-0.5" : "right-0.5",
                )}
              >
                {h === "start" ? "Début" : "Fin"} {formatTime(h === "start" ? start : end)}
              </span>
            </div>
          ))}
          {playhead !== null && (
            <div className="pointer-events-none absolute top-0 bottom-0 w-0.5 bg-ok" style={{ left: px(playhead) - 1 }} />
          )}
        </div>
      </div>
    </div>
  );
}
