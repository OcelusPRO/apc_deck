import { useEffect, useState, type MouseEvent as ReactMouseEvent } from "react";
import { cx, padHex, textOn } from "@apcdeck/web";
import { cmd } from "./api";
import type { Pager, PagerSlot, PluginView } from "./types";

const same = (a: PagerSlot, b: PagerSlot) => a.row === b.row && a.col === b.col && a.x === b.x && a.y === b.y;

interface Drag {
  id: string;
  from: PagerSlot | null;
  x: number;
  y: number;
  moved: boolean;
}

function slotAt(x: number, y: number): PagerSlot | null {
  const el = document.elementFromPoint(x, y)?.closest<HTMLElement>("[data-slot]");
  return el ? (JSON.parse(el.dataset.slot ?? "null") as PagerSlot) : null;
}

/** Pages du gestionnaire : on glisse un plugin sur un pad, on déplace un pad, on le lâche dehors pour le retirer. */
export function PagerEditor({ pager, plugins }: { pager: Pager; plugins: PluginView[] }) {
  const [drag, setDrag] = useState<Drag | null>(null);
  const [pointer, setPointer] = useState({ x: 0, y: 0 });
  const [target, setTarget] = useState<PagerSlot | null>(null);
  const { current, slots } = pager;
  const others = plugins.filter((p) => !p.manager);
  const byId = new Map(plugins.map((p) => [p.id, p]));

  useEffect(() => {
    if (!drag) return;
    const move = (e: MouseEvent) => {
      if (!drag.moved && Math.hypot(e.clientX - drag.x, e.clientY - drag.y) > 4) setDrag({ ...drag, moved: true });
      setPointer({ x: e.clientX, y: e.clientY });
      setTarget(slotAt(e.clientX, e.clientY));
    };
    const up = (e: MouseEvent) => {
      setDrag(null);
      setTarget(null);
      if (!drag.moved) return;
      const to = slotAt(e.clientX, e.clientY);
      if (to && drag.from) void cmd("pagerMove", { from: drag.from, to });
      else if (to) void cmd("pagerPlace", { ...to, id: drag.id });
      else if (drag.from) void cmd("pagerClear", drag.from);
    };
    window.addEventListener("mousemove", move);
    window.addEventListener("mouseup", up);
    return () => {
      window.removeEventListener("mousemove", move);
      window.removeEventListener("mouseup", up);
    };
  }, [drag]);

  const startDrag = (id: string, from: PagerSlot | null) => (e: ReactMouseEvent) => {
    if (e.button !== 0) return;
    e.preventDefault();
    setDrag({ id, from, x: e.clientX, y: e.clientY, moved: false });
    setPointer({ x: e.clientX, y: e.clientY });
  };

  const counts = new Map<string, number>();
  slots.forEach((s) => counts.set(`${s.row}.${s.col}`, (counts.get(`${s.row}.${s.col}`) ?? 0) + 1));
  const dragged = drag?.moved ? byId.get(drag.id) : undefined;

  return (
    <div id="pager" className="flex gap-7 p-4">
      <div className="flex flex-col gap-3">
        <div className="flex items-baseline gap-3">
          <h3 className="m-0 text-sm font-semibold">Page {current.row + 1}·{current.col + 1}</h3>
          <span className="text-xs text-muted">colonne verte × ligne rouge</span>
        </div>
        <div className="grid w-fit grid-cols-8 gap-[3px]">
          {Array.from({ length: 40 }, (_, i) => {
            const row = Math.floor(i / 8);
            const col = i % 8;
            const count = counts.get(`${row}.${col}`) ?? 0;
            const isCurrent = row === current.row && col === current.col;
            return (
              <button
                key={i}
                type="button"
                title={`Page ${row + 1}·${col + 1}`}
                onClick={() => cmd("pagerPage", { row, col })}
                className={cx("page-cell h-5 w-7.5 cursor-pointer rounded-[3px] text-[10px]", isCurrent ? "current bg-accent font-semibold text-on-accent" : count ? "bg-[#4a4458]" : "bg-white/5")}
              >
                {count || ""}
              </button>
            );
          })}
        </div>
        <div className="grid grid-cols-[repeat(8,76px)] gap-2">
          {Array.from({ length: 40 }, (_, i) => {
            const slot: PagerSlot = { ...current, x: i % 8, y: Math.floor(i / 8) };
            const id = slots.find((s) => same(s, slot))?.id;
            const p = id ? byId.get(id) : undefined;
            const available = p?.status === "ENABLED";
            const color = available && p ? padHex(p.color) : undefined;
            return (
              <div
                key={i}
                data-slot={JSON.stringify(slot)}
                onMouseDown={id ? startDrag(id, slot) : undefined}
                className={cx(
                  "slot relative flex h-[76px] items-center justify-center rounded-lg border border-line p-1.5 text-center text-xs font-semibold select-none",
                  !id && "bg-pad-off",
                  id && "cursor-grab",
                  id && !available && "bg-[#4a4a50] text-[#ccc]",
                  target && same(target, slot) && "outline-3 outline-accent",
                )}
                style={color ? { background: color, color: textOn(color) } : undefined}
              >
                {id && (p?.name ?? id)}
                {id && !available && <span className="absolute inset-x-0 bottom-0.5 text-[9px] font-normal">{p ? "désactivé" : "absent"}</span>}
                {id && (
                  <span className="absolute top-0.5 right-1.5 cursor-pointer text-[11px] opacity-70" onMouseDown={(e) => e.stopPropagation()} onClick={() => cmd("pagerClear", slot)}>
                    ✕
                  </span>
                )}
              </div>
            );
          })}
        </div>
        <p className="text-xs text-muted">
          Glisse un plugin sur un pad · glisse un pad pour le déplacer (échange si occupé) · lâche-le hors de la grille pour le retirer.
        </p>
      </div>
      <div className="flex w-[220px] flex-col gap-1.5">
        <h3 className="m-0 text-sm font-semibold">Plugins</h3>
        {others.length === 0 && <p className="text-xs text-muted">Aucun plugin installé.</p>}
        {others.map((p) => {
          const count = slots.filter((s) => s.id === p.id).length;
          return (
            <div key={p.id} onMouseDown={startDrag(p.id, null)} className="palette-item flex cursor-grab items-center gap-2 rounded-lg bg-surface-2 px-2.5 py-2 select-none">
              <span className="h-3 w-3 shrink-0 rounded-full" style={{ background: padHex(p.color) }} />
              <span className="min-w-0 flex-1 truncate">{p.name}</span>
              <span className="text-xs text-muted">{count ? `×${count}` : "non placé"}</span>
            </div>
          );
        })}
      </div>
      {dragged && (
        <div
          className="pointer-events-none fixed z-30 rounded-lg px-3 py-1.5 text-xs font-semibold shadow-[0_6px_20px_#0008]"
          style={{ left: pointer.x + 12, top: pointer.y + 12, background: padHex(dragged.color), color: textOn(padHex(dragged.color)) }}
        >
          {dragged.name}
        </div>
      )}
    </div>
  );
}
